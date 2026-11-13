package com.viby.core.playback;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import com.viby.core.util.ProcessPriority;

/**
 * Plays local files and online streams: ffmpeg decodes to 16-bit PCM, per-track loudness gain,
 * the equalizer and the volume are applied in Java, and the result goes to the sound device.
 *
 * <p>One session keeps the sound device open across tracks: when the next track is known
 * ({@link #setNext}) it continues without a gap, or with a crossfade ({@link #setCrossfadeMs}).
 * Seeking restarts the session with ffmpeg {@code -ss}.
 */
public final class AudioEngine implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AudioEngine.class.getName());

    public static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;
    private static final int FRAME_BYTES = 2 * CHANNELS;
    /**
     * Device buffer: big enough to ride out a busy CPU (a download converting to MP3 broke up
     * the sound at 150 ms), small enough that volume and EQ changes still feel immediate.
     * Pause stops the line itself, so it is instant regardless.
     */
    private static final int LINE_BUFFER_BYTES = SAMPLE_RATE * FRAME_BYTES * 350 / 1000;
    private static final int CHUNK_FRAMES = 1024;
    /** The next track's decoder starts this long before the end, so it is ready in time. */
    private static final long PREPARE_NEXT_MS = 15_000;
    /** Largest gain step per chunk (~21 ms): a loudness value arriving mid-track fades in. */
    private static final float GAIN_STEP = 0.02f;

    public interface Listener {
        /** The last source played to its end. Called on the audio thread. */
        void onEnded();

        /**
         * The sound moved on to the source given to {@link #setNext} (gapless or crossfaded);
         * {@link #positionMs()} now counts in that track. Called on the audio thread.
         */
        default void onAdvanced(String source) {
        }

        /** Decoding or the sound device failed. Called on the audio thread. */
        void onError(String message);
    }

    /** The track that follows the current one. */
    public record Next(String source, long durationMs, float gainDb) {
    }

    /** A running decoder: PCM s16le, 48 kHz, stereo. */
    interface Decoder {
        InputStream pcm();

        /** Error output so far (ffmpeg stderr). */
        String errors();

        int waitFor() throws InterruptedException;

        void close();
    }

    interface DecoderFactory {
        Decoder open(String source, long startMs) throws IOException;
    }

    /** The sound device. */
    interface Output {
        void start();

        void stop();

        void flush();

        void drain();

        void close();

        int write(byte[] bytes, int offset, int length);

        /** Frames that have been played (heard), not just written. */
        long playedFrames();
    }

    interface OutputFactory {
        Output open() throws LineUnavailableException;
    }

    private final DecoderFactory decoders;
    private final OutputFactory outputs;
    private final Equalizer equalizer = new Equalizer(SAMPLE_RATE, CHANNELS);
    private volatile float volume = 1f;
    private volatile int crossfadeMs;
    private volatile @Nullable Session session;
    private volatile @Nullable Listener listener;

    public AudioEngine(Supplier<Path> ffmpeg) {
        this(ffmpegDecoders(ffmpeg), AudioEngine::deviceOutput);
    }

    AudioEngine(DecoderFactory decoders, OutputFactory outputs) {
        this.decoders = decoders;
        this.outputs = outputs;
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public Equalizer equalizer() {
        return equalizer;
    }

    /** @param source absolute file path or http(s) stream URL */
    public void play(String source, long startMs, boolean paused) {
        play(source, startMs, paused, 0, 0f);
    }

    /**
     * @param durationMs track length, needed for a crossfade (0 when unknown)
     * @param gainDb     loudness normalization gain for this track
     */
    public synchronized void play(String source, long startMs, boolean paused, long durationMs, float gainDb) {
        start(new Segment(source, Math.max(0, startMs), durationMs, gainDb), paused, null);
    }

    private void start(Segment first, boolean paused, @Nullable Next next) {
        stopSession();
        Session started = new Session(first, paused);
        started.requestedNext = next;
        session = started;
        started.start();
    }

    /** The track to continue with after the current one; null ends playback there. */
    public void setNext(@Nullable Next next) {
        Session current = session;
        if (current != null) {
            current.requestedNext = next;
        }
    }

    /** Loudness gain of a track that is playing or up next, e.g. once it was measured. */
    public void setGain(String source, float gainDb) {
        Session current = session;
        if (current != null) {
            current.setGain(source, gainDb);
        }
    }

    /** 0 = gapless; otherwise the tracks overlap for this long. */
    public void setCrossfadeMs(int crossfadeMs) {
        this.crossfadeMs = Math.max(0, crossfadeMs);
    }

    public synchronized void pause() {
        Session current = session;
        if (current != null) {
            current.setPaused(true);
        }
    }

    public synchronized void resume() {
        Session current = session;
        if (current != null) {
            current.setPaused(false);
        }
    }

    /** Restarts decoding of the track that is heard at {@code positionMs}, keeping the paused state. */
    public synchronized void seek(long positionMs) {
        Session current = session;
        if (current != null) {
            Segment heard = current.heardSegment();
            // the queue did not change: the following track stays
            Next next = current.heardIsLast() ? current.requestedNext : null;
            start(new Segment(heard.source, Math.max(0, positionMs), heard.durationMs, heard.gainDb), current.paused, next);
        }
    }

    public synchronized void stop() {
        stopSession();
    }

    /** 0..1 slider value; squared for a perceptually even curve. */
    public void setVolume(float volume) {
        float clamped = Math.max(0f, Math.min(1f, volume));
        this.volume = clamped * clamped;
    }

    public long positionMs() {
        Session current = session;
        return current != null ? current.positionMs() : 0;
    }

    public boolean hasSource() {
        return session != null;
    }

    private void stopSession() {
        Session current = session;
        session = null;
        if (current != null) {
            current.stop();
        }
    }

    @Override
    public void close() {
        stop();
    }

    static List<String> command(Path ffmpeg, String source, long startMs) {
        List<String> command = new ArrayList<>(List.of(ffmpeg.toString(),
                "-hide_banner", "-loglevel", "error", "-nostdin"));
        if (startMs > 0) {
            command.add("-ss");
            command.add(String.format(Locale.ROOT, "%.3f", startMs / 1000.0));
        }
        if (source.startsWith("http://") || source.startsWith("https://")) {
            command.addAll(List.of("-reconnect", "1", "-reconnect_streamed", "1",
                    "-reconnect_delay_max", "5"));
        }
        command.addAll(List.of("-i", source, "-vn", "-f", "s16le", "-acodec", "pcm_s16le",
                "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(CHANNELS), "pipe:1"));
        return command;
    }

    /** dB → linear factor. */
    static float linear(float gainDb) {
        return (float) Math.pow(10, gainDb / 20.0);
    }

    /** One track inside a session. */
    private static final class Segment {
        final String source;
        final long startMs;
        final long durationMs;
        volatile float gainDb;
        /** Applied factor; moves towards {@link #gainDb} a little per chunk. */
        float appliedGain;
        @Nullable Decoder decoder;
        long decodedFrames;
        /** Output frame at which this track starts to be heard. */
        long startFrame = -1;

        Segment(String source, long startMs, long durationMs, float gainDb) {
            this.source = source;
            this.startMs = startMs;
            this.durationMs = durationMs;
            this.gainDb = gainDb;
            this.appliedGain = linear(gainDb);
        }

        long decodedMs() {
            return startMs + decodedFrames * 1000 / SAMPLE_RATE;
        }

        float nextGain() {
            float target = linear(gainDb);
            appliedGain += Math.max(-GAIN_STEP, Math.min(GAIN_STEP, target - appliedGain));
            return appliedGain;
        }

        void close() {
            Decoder current = decoder;
            decoder = null;
            if (current != null) {
                current.close();
            }
        }
    }

    private final class Session {
        volatile boolean paused;
        private volatile boolean stopped;
        volatile @Nullable Next requestedNext;
        /** Tracks in the order they are heard; the last one is being decoded. */
        private final List<Segment> segments = new ArrayList<>();
        private int announced;
        private volatile @Nullable Output output;
        private long framesWritten;

        Session(Segment first, boolean paused) {
            this.paused = paused;
            first.startFrame = 0;
            segments.add(first);
        }

        void start() {
            Thread thread = new Thread(this::run, "viby-audio");
            thread.setDaemon(true);
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
        }

        synchronized void setPaused(boolean value) {
            paused = value;
            Output current = output;
            if (current != null) {
                if (value) {
                    current.stop();
                } else {
                    current.start();
                }
            }
            notifyAll();
        }

        void setGain(String source, float gainDb) {
            synchronized (segments) {
                for (Segment segment : segments) {
                    if (segment.source.equals(source)) {
                        segment.gainDb = gainDb;
                    }
                }
            }
        }

        /**
         * Blocks the decoder while paused. Writing to a stopped line does not block on every
         * mixer (Windows returns immediately), so pausing must hold the loop explicitly or the
         * decoder runs to the end of the file and reports the track as finished.
         */
        private synchronized void awaitResume() throws InterruptedException {
            while (paused && !stopped) {
                wait();
            }
        }

        Segment heardSegment() {
            Output current = output;
            long played = current != null ? current.playedFrames() : 0;
            synchronized (segments) {
                Segment heard = segments.get(0);
                for (Segment segment : segments) {
                    if (segment.startFrame >= 0 && segment.startFrame <= played) {
                        heard = segment;
                    }
                }
                return heard;
            }
        }

        /** True unless a crossfade into the next track is still going on. */
        boolean heardIsLast() {
            synchronized (segments) {
                return heardSegment() == segments.get(segments.size() - 1);
            }
        }

        long positionMs() {
            Output current = output;
            long played = current != null ? current.playedFrames() : 0;
            Segment heard = heardSegment();
            return heard.startMs + Math.max(0, played - heard.startFrame) * 1000 / SAMPLE_RATE;
        }

        void stop() {
            synchronized (this) {
                stopped = true;
                notifyAll();
            }
            synchronized (segments) {
                segments.forEach(Segment::close);
            }
            Output current = output;
            if (current != null) {
                current.stop();
                current.flush();
                current.close();
            }
        }

        /** Announces every track that started to be heard since the last call. */
        private void announceHeard() {
            Output current = output;
            if (current == null) {
                return;
            }
            long played = current.playedFrames();
            while (true) {
                Segment following;
                synchronized (segments) {
                    if (announced + 1 >= segments.size()) {
                        return;
                    }
                    following = segments.get(announced + 1);
                    if (following.startFrame < 0 || following.startFrame > played) {
                        return;
                    }
                    announced++;
                }
                Listener listener = AudioEngine.this.listener;
                if (listener != null && session == this && !stopped) {
                    listener.onAdvanced(following.source);
                }
            }
        }

        private void run() {
            Segment first = segments.get(0);
            try {
                first.decoder = decoders.open(first.source, first.startMs);
                Output device = outputs.open();
                synchronized (this) {
                    if (stopped) {
                        device.close();
                        first.close();
                        return;
                    }
                    output = device;
                    if (!paused) {
                        device.start();
                    }
                }
                play(device);
                if (stopped || session != this) {
                    return;
                }
                // a pause right at the end must not let drain() return early and finish the track
                awaitResume();
                if (!stopped) {
                    device.drain();
                }
                announceHeard();
                if (stopped || session != this) {
                    return;
                }
                Segment last = segments.get(segments.size() - 1);
                Decoder decoder = last.decoder;
                int exit = decoder != null ? decoder.waitFor() : 0;
                Listener current = listener;
                if (current == null) {
                    return;
                }
                if (exit != 0 && framesWritten == 0) {
                    String message = decoder.errors().trim();
                    current.onError(message.isEmpty() ? "ffmpeg exited with code " + exit : message);
                } else {
                    current.onEnded();
                }
            } catch (LineUnavailableException | IllegalArgumentException e) {
                report("Звуковое устройство недоступно: " + e.getMessage(), e);
            } catch (IOException e) {
                if (!stopped) {
                    report("Не удалось воспроизвести: " + e.getMessage(), e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                synchronized (segments) {
                    segments.forEach(Segment::close);
                }
                Output device = output;
                if (device != null) {
                    device.close();
                }
            }
        }

        /** Decodes, mixes and writes until the last track ends or the session stops. */
        private void play(Output device) throws IOException, InterruptedException {
            byte[] bytes = new byte[CHUNK_FRAMES * FRAME_BYTES];
            byte[] nextBytes = new byte[CHUNK_FRAMES * FRAME_BYTES];
            float[] samples = new float[CHUNK_FRAMES * CHANNELS];
            float[] nextSamples = new float[CHUNK_FRAMES * CHANNELS];
            Segment current = segments.get(0);
            Segment next = null;
            // crossfade in progress: frames mixed so far out of mixFrames
            long mixed = -1;
            long mixFrames = 0;

            while (!stopped) {
                next = prepareNext(current, next);
                int frames = readFrames(current, bytes);
                if (frames == 0) {
                    // the current track is over
                    if (mixed >= 0 || (next != null && next.decoder != null)) {
                        if (mixed < 0) {
                            startHearing(next);
                        }
                        current.close();
                        current = next;
                        next = null;
                        mixed = -1;
                        continue;
                    }
                    return;
                }
                toFloats(bytes, samples, frames);
                applyGain(samples, frames, current.nextGain());

                int fade = crossfadeMs;
                if (mixed < 0 && fade > 0 && next != null && next.decoder != null && current.durationMs > 0
                        && current.decodedMs() >= current.durationMs - fade) {
                    mixed = 0;
                    mixFrames = Math.max(1, (long) fade * SAMPLE_RATE / 1000);
                    startHearing(next);
                }
                if (mixed >= 0 && next != null) {
                    // exactly as many frames as the current chunk holds, or the next track loses some
                    int nextFrames = readFrames(next, nextBytes, frames);
                    toFloats(nextBytes, nextSamples, nextFrames);
                    java.util.Arrays.fill(nextSamples, nextFrames * CHANNELS, frames * CHANNELS, 0f);
                    applyGain(nextSamples, nextFrames, next.nextGain());
                    for (int frame = 0; frame < frames; frame++) {
                        double t = Math.min(1.0, (mixed + frame) / (double) mixFrames);
                        // equal power: the loudness stays even through the overlap
                        float out = (float) Math.cos(t * Math.PI / 2);
                        float in = (float) Math.sin(t * Math.PI / 2);
                        for (int c = 0; c < CHANNELS; c++) {
                            int i = frame * CHANNELS + c;
                            samples[i] = samples[i] * out + nextSamples[i] * in;
                        }
                    }
                    mixed += frames;
                    if (mixed >= mixFrames) {
                        // the length was a bit off: the faded-out rest is not needed
                        current.close();
                        current = next;
                        next = null;
                        mixed = -1;
                    }
                }
                write(device, bytes, samples, frames);
                announceHeard();
            }
        }

        /** Opens the following track's decoder once the current one nears its end. */
        private @Nullable Segment prepareNext(Segment current, @Nullable Segment prepared) throws IOException {
            Next wanted = requestedNext;
            if (prepared != null && prepared.startFrame >= 0) {
                return prepared; // already being heard (crossfade)
            }
            if (prepared != null && (wanted == null || !wanted.source().equals(prepared.source)
                    || wanted.gainDb() != prepared.gainDb)) {
                if (wanted == null || !wanted.source().equals(prepared.source)) {
                    prepared.close();
                    prepared = null;
                } else {
                    prepared.gainDb = wanted.gainDb();
                }
            }
            if (wanted == null || prepared != null) {
                return prepared;
            }
            long remaining = current.durationMs > 0 ? current.durationMs - current.decodedMs() : 0;
            if (current.durationMs > 0 && remaining > PREPARE_NEXT_MS) {
                return null;
            }
            Segment segment = new Segment(wanted.source(), 0, wanted.durationMs(), wanted.gainDb());
            try {
                segment.decoder = decoders.open(segment.source, 0);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Next track cannot be opened: " + segment.source, e);
                return null;
            }
            return segment;
        }

        private void startHearing(Segment next) {
            synchronized (segments) {
                next.startFrame = framesWritten;
                segments.add(next);
            }
            // later tracks are chosen anew by the app after it hears about this one
            requestedNext = null;
        }

        private void write(Output device, byte[] bytes, float[] samples, int frames) throws InterruptedException {
            int count = frames * CHANNELS;
            equalizer.process(samples, count);
            float level = volume;
            for (int i = 0; i < count; i++) {
                float value = Math.max(-1f, Math.min(1f, samples[i] * level));
                short pcm = (short) Math.round(value * 32767f);
                bytes[2 * i] = (byte) pcm;
                bytes[2 * i + 1] = (byte) (pcm >> 8);
            }
            int length = frames * FRAME_BYTES;
            int offset = 0;
            while (offset < length && !stopped) {
                awaitResume();
                offset += device.write(bytes, offset, length - offset);
            }
            framesWritten += frames;
        }

        private void report(String message, Exception e) {
            LOG.log(Level.WARNING, message, e);
            Listener current = listener;
            if (current != null && session == this && !stopped) {
                current.onError(message);
            }
        }
    }

    /** Reads up to one chunk of whole frames; 0 at the end of the track. */
    private static int readFrames(Segment segment, byte[] bytes) throws IOException {
        return readFrames(segment, bytes, bytes.length / FRAME_BYTES);
    }

    private static int readFrames(Segment segment, byte[] bytes, int maxFrames) throws IOException {
        Decoder decoder = segment.decoder;
        if (decoder == null) {
            return 0;
        }
        InputStream pcm = decoder.pcm();
        int wanted = maxFrames * FRAME_BYTES;
        int filled = 0;
        while (filled < wanted) {
            int read = pcm.read(bytes, filled, wanted - filled);
            if (read < 0) {
                break;
            }
            filled += read;
        }
        int frames = filled / FRAME_BYTES;
        segment.decodedFrames += frames;
        return frames;
    }

    private static void toFloats(byte[] bytes, float[] samples, int frames) {
        int count = frames * CHANNELS;
        for (int i = 0; i < count; i++) {
            short value = (short) ((bytes[2 * i] & 0xff) | (bytes[2 * i + 1] << 8));
            samples[i] = value / 32768f;
        }
    }

    private static void applyGain(float[] samples, int frames, float gain) {
        if (gain == 1f) {
            return;
        }
        int count = frames * CHANNELS;
        for (int i = 0; i < count; i++) {
            samples[i] *= gain;
        }
    }

    // ------------------------------------------------------------------ real devices

    static DecoderFactory ffmpegDecoders(Supplier<Path> ffmpeg) {
        return (source, startMs) -> {
            Process process = new ProcessBuilder(command(ffmpeg.get(), source, startMs)).start();
            ProcessPriority.playback(process);
            StringBuilder errors = new StringBuilder();
            Thread reader = new Thread(() -> drain(process.getErrorStream(), errors), "viby-ffmpeg-err");
            reader.setDaemon(true);
            reader.start();
            return new Decoder() {
                @Override
                public InputStream pcm() {
                    return process.getInputStream();
                }

                @Override
                public String errors() {
                    synchronized (errors) {
                        return errors.toString();
                    }
                }

                @Override
                public int waitFor() throws InterruptedException {
                    return process.waitFor();
                }

                @Override
                public void close() {
                    process.destroyForcibly();
                }
            };
        };
    }

    private static Output deviceOutput() throws LineUnavailableException {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false);
        SourceDataLine line = AudioSystem.getSourceDataLine(format);
        line.open(format, LINE_BUFFER_BYTES);
        return new Output() {
            @Override
            public void start() {
                line.start();
            }

            @Override
            public void stop() {
                line.stop();
            }

            @Override
            public void flush() {
                line.flush();
            }

            @Override
            public void drain() {
                line.drain();
            }

            @Override
            public void close() {
                line.close();
            }

            @Override
            public int write(byte[] bytes, int offset, int length) {
                return line.write(bytes, offset, length);
            }

            @Override
            public long playedFrames() {
                return line.getLongFramePosition();
            }
        };
    }

    private static void drain(InputStream stream, StringBuilder sink) {
        try {
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            synchronized (sink) {
                sink.append(text);
            }
        } catch (IOException ignored) {
            // the process was destroyed
        }
    }
}
