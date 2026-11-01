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

/**
 * Plays local files and online streams: ffmpeg decodes to 16-bit PCM, the equalizer and
 * volume are applied in Java, and the result goes to the default sound device.
 * Seeking restarts ffmpeg with {@code -ss}. One session plays at a time.
 */
public final class AudioEngine implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AudioEngine.class.getName());

    public static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;
    private static final int FRAME_BYTES = 2 * CHANNELS;
    /** ~150 ms device buffer keeps pause, seek and volume responsive. */
    private static final int LINE_BUFFER_BYTES = SAMPLE_RATE * FRAME_BYTES * 150 / 1000;
    private static final int CHUNK_FRAMES = 1024;

    public interface Listener {
        /** The current source played to its end. Called on the audio thread. */
        void onEnded();

        /** Decoding or the sound device failed. Called on the audio thread. */
        void onError(String message);
    }

    private final Supplier<Path> ffmpeg;
    private final Equalizer equalizer = new Equalizer(SAMPLE_RATE, CHANNELS);
    private volatile float gain = 1f;
    private volatile @Nullable Session session;
    private volatile @Nullable Listener listener;

    public AudioEngine(Supplier<Path> ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public Equalizer equalizer() {
        return equalizer;
    }

    /** @param source absolute file path or http(s) stream URL */
    public synchronized void play(String source, long startMs, boolean paused) {
        stopSession();
        Session next = new Session(source, Math.max(0, startMs), paused);
        session = next;
        next.start();
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

    /** Restarts decoding at {@code positionMs}, keeping the paused state. */
    public synchronized void seek(long positionMs) {
        Session current = session;
        if (current != null) {
            play(current.source, positionMs, current.paused);
        }
    }

    public synchronized void stop() {
        stopSession();
    }

    /** 0..1 slider value; squared for a perceptually even curve. */
    public void setVolume(float volume) {
        float clamped = Math.max(0f, Math.min(1f, volume));
        gain = clamped * clamped;
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

    private final class Session {
        final String source;
        final long startMs;
        volatile boolean paused;
        private volatile boolean stopped;
        private volatile @Nullable Process process;
        private volatile @Nullable SourceDataLine line;

        Session(String source, long startMs, boolean paused) {
            this.source = source;
            this.startMs = startMs;
            this.paused = paused;
        }

        void start() {
            Thread thread = new Thread(this::run, "viby-audio");
            thread.setDaemon(true);
            thread.start();
        }

        synchronized void setPaused(boolean value) {
            paused = value;
            SourceDataLine current = line;
            if (current != null) {
                if (value) {
                    current.stop();
                } else {
                    current.start();
                }
            }
            notifyAll();
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

        long positionMs() {
            SourceDataLine current = line;
            return startMs + (current != null ? current.getMicrosecondPosition() / 1000 : 0);
        }

        void stop() {
            synchronized (this) {
                stopped = true;
                notifyAll();
            }
            Process current = process;
            if (current != null) {
                current.destroyForcibly();
            }
            SourceDataLine output = line;
            if (output != null) {
                output.stop();
                output.flush();
                output.close();
            }
        }

        private void run() {
            StringBuilder errors = new StringBuilder();
            long framesWritten = 0;
            try {
                Process decoder = new ProcessBuilder(command(ffmpeg.get(), source, startMs)).start();
                process = decoder;
                Thread errReader = new Thread(() -> drain(decoder.getErrorStream(), errors), "viby-ffmpeg-err");
                errReader.setDaemon(true);
                errReader.start();

                AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false);
                SourceDataLine output = AudioSystem.getSourceDataLine(format);
                output.open(format, LINE_BUFFER_BYTES);
                synchronized (this) {
                    if (stopped) {
                        output.close();
                        return;
                    }
                    line = output;
                    if (!paused) {
                        output.start();
                    }
                }

                InputStream pcm = decoder.getInputStream();
                byte[] bytes = new byte[CHUNK_FRAMES * FRAME_BYTES];
                float[] samples = new float[CHUNK_FRAMES * CHANNELS];
                int filled = 0;
                while (!stopped) {
                    int read = pcm.read(bytes, filled, bytes.length - filled);
                    if (read < 0) {
                        break;
                    }
                    filled += read;
                    int usable = filled - filled % FRAME_BYTES;
                    if (usable == 0) {
                        continue;
                    }
                    int count = usable / 2;
                    for (int i = 0; i < count; i++) {
                        short value = (short) ((bytes[2 * i] & 0xff) | (bytes[2 * i + 1] << 8));
                        samples[i] = value / 32768f;
                    }
                    equalizer.process(samples, count);
                    float volume = gain;
                    for (int i = 0; i < count; i++) {
                        float value = Math.max(-1f, Math.min(1f, samples[i] * volume));
                        short pcmValue = (short) Math.round(value * 32767f);
                        bytes[2 * i] = (byte) pcmValue;
                        bytes[2 * i + 1] = (byte) (pcmValue >> 8);
                    }
                    int offset = 0;
                    while (offset < usable && !stopped) {
                        awaitResume();
                        offset += output.write(bytes, offset, usable - offset);
                    }
                    framesWritten += usable / FRAME_BYTES;
                    // keep a partial frame for the next read
                    System.arraycopy(bytes, usable, bytes, 0, filled - usable);
                    filled -= usable;
                }
                // a pause right at the end must not let drain() return early and finish the track
                awaitResume();
                if (!stopped) {
                    output.drain();
                }
                int exit = decoder.waitFor();
                if (stopped || session != this) {
                    return;
                }
                Listener current = listener;
                if (exit != 0 && framesWritten == 0) {
                    String message = errors.toString().trim();
                    if (current != null) {
                        current.onError(message.isEmpty() ? "ffmpeg exited with code " + exit : message);
                    }
                } else if (current != null) {
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
                Process decoder = process;
                if (decoder != null) {
                    decoder.destroyForcibly();
                }
                SourceDataLine output = line;
                if (output != null) {
                    output.close();
                }
            }
        }

        private void report(String message, Exception e) {
            LOG.log(Level.WARNING, message, e);
            Listener current = listener;
            if (current != null && session == this && !stopped) {
                current.onError(message);
            }
        }
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
