package com.viby.core.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Gapless playback, crossfade and loudness gain, with fake decoders and a fake sound device. */
class AudioEngineTest {

    private static final int RATE = AudioEngine.SAMPLE_RATE;

    /** Each "file" is a constant tone: every sample has the same value. */
    private record Tone(int frames, short value) {
        long durationMs() {
            return frames * 1000L / RATE;
        }
    }

    private final Map<String, Tone> files = Map.of(
            "a.mp3", new Tone(RATE / 10, (short) 1000),   // 100 ms
            "b.mp3", new Tone(RATE / 20, (short) 2000),   // 50 ms
            "c.mp3", new Tone(RATE / 20, (short) 3000),
            // ends on a partial chunk (1024 frames per chunk)
            "odd.mp3", new Tone(3 * 1024 + 300, (short) 1500));

    /** Plays instantly: every written frame counts as heard. */
    private static final class FakeOutput implements AudioEngine.Output {
        final ByteArrayOutputStream written = new ByteArrayOutputStream();

        @Override public void start() { }
        @Override public void stop() { }
        @Override public void flush() { }
        @Override public void drain() { }
        @Override public void close() { }

        @Override
        public synchronized int write(byte[] bytes, int offset, int length) {
            written.write(bytes, offset, length);
            return length;
        }

        @Override
        public synchronized long playedFrames() {
            return written.size() / 4;
        }

        synchronized short[] left() {
            byte[] bytes = written.toByteArray();
            short[] samples = new short[bytes.length / 4];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (short) ((bytes[4 * i] & 0xff) | (bytes[4 * i + 1] << 8));
            }
            return samples;
        }
    }

    private final FakeOutput output = new FakeOutput();
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch ended = new CountDownLatch(1);

    private AudioEngine engine() {
        AudioEngine engine = new AudioEngine(this::decoder, () -> output);
        engine.setListener(new AudioEngine.Listener() {
            @Override
            public void onEnded() {
                events.add("ended");
                ended.countDown();
            }

            @Override
            public void onAdvanced(String source) {
                events.add("advanced");
                assertEquals("b.mp3", source);
            }

            @Override
            public void onError(String message) {
                events.add("error " + message);
                ended.countDown();
            }
        });
        return engine;
    }

    private AudioEngine.Decoder decoder(String source, long startMs) {
        Tone tone = files.get(source);
        int skip = (int) (startMs * RATE / 1000);
        int frames = Math.max(0, tone.frames() - skip);
        byte[] pcm = new byte[frames * 4];
        for (int i = 0; i < frames * 2; i++) {
            pcm[2 * i] = (byte) tone.value();
            pcm[2 * i + 1] = (byte) (tone.value() >> 8);
        }
        InputStream stream = new ByteArrayInputStream(pcm);
        return new AudioEngine.Decoder() {
            @Override public InputStream pcm() { return stream; }
            @Override public String errors() { return ""; }
            @Override public int waitFor() { return 0; }
            @Override public void close() { }
        };
    }

    private short[] playAll(AudioEngine engine, String first, AudioEngine.Next... queue) throws Exception {
        // the next track is known from the start, like the app sets it right after loading
        Tone tone = files.get(first);
        engine.play(first, 0, true, tone.durationMs(), 0f);
        if (queue.length > 0) {
            engine.setNext(queue[0]);
        }
        engine.resume();
        assertTrue(ended.await(10, TimeUnit.SECONDS), "playback did not end");
        return output.left();
    }

    private AudioEngine.Next next(String source, float gainDb) {
        return new AudioEngine.Next(source, files.get(source).durationMs(), gainDb);
    }

    @Test
    void nextTrackFollowsWithoutAGap() throws Exception {
        short[] heard = playAll(engine(), "a.mp3", next("b.mp3", 0f));

        Tone a = files.get("a.mp3");
        Tone b = files.get("b.mp3");
        assertEquals(a.frames() + b.frames(), heard.length);
        for (int i = 0; i < heard.length; i++) {
            short expected = i < a.frames() ? a.value() : b.value();
            assertTrue(Math.abs(heard[i] - expected) <= 1, "frame " + i + " = " + heard[i]);
        }
        assertEquals(List.of("advanced", "ended"), events);
    }

    @Test
    void crossfadeOverlapsTheTracks() throws Exception {
        AudioEngine engine = engine();
        int fadeMs = 20;
        engine.setCrossfadeMs(fadeMs);
        short[] heard = playAll(engine, "a.mp3", next("b.mp3", 0f));

        Tone a = files.get("a.mp3");
        Tone b = files.get("b.mp3");
        int overlap = fadeMs * RATE / 1000;
        // the overlap starts on a chunk boundary, so it may begin a little later than asked
        assertTrue(heard.length <= a.frames() + b.frames() - overlap + 1024, "length " + heard.length);
        assertTrue(heard.length >= a.frames() + b.frames() - overlap - 1024, "length " + heard.length);
        assertEquals(a.value(), heard[0], 1);
        assertEquals(b.value(), heard[heard.length - 1], 1);
        // during the overlap both tracks are heard at once
        boolean mixed = false;
        for (short sample : heard) {
            mixed |= sample > a.value() + 50 && sample < b.value() - 50;
        }
        assertTrue(mixed, "no mixed samples");
        assertEquals(List.of("advanced", "ended"), events);
    }

    @Test
    void loudnessGainIsAppliedPerTrack() throws Exception {
        // -6.02 dB halves the samples
        AudioEngine engine = engine();
        short[] heard = playAll(engine, "a.mp3", next("b.mp3", -6.0206f));
        Tone a = files.get("a.mp3");
        assertEquals(a.value(), heard[10], 1);
        assertEquals(1000, heard[heard.length - 1], 2);
    }

    @Test
    void withoutANextTrackPlaybackEnds() throws Exception {
        short[] heard = playAll(engine(), "c.mp3");
        assertEquals(files.get("c.mp3").frames(), heard.length);
        assertEquals(List.of("ended"), events);
    }

    @Test
    void positionCountsInTheTrackThatIsHeard() throws Exception {
        AudioEngine engine = engine();
        playAll(engine, "a.mp3", next("b.mp3", 0f));
        // after the switch the position belongs to b, not to a + b
        long position = engine.positionMs();
        assertEquals(files.get("b.mp3").durationMs(), position, 2);
    }

    @Test
    void linearGain() {
        assertEquals(1f, AudioEngine.linear(0f), 1e-6);
        assertEquals(0.5f, AudioEngine.linear(-6.0206f), 1e-4);
        assertEquals(2f, AudioEngine.linear(6.0206f), 1e-3);
    }

    /** The overlap reaches the last, shorter chunk of the first track (found with real files). */
    @Test
    void crossfadeIntoTheLastPartialChunkKeepsEveryFrameOfTheNextTrack() throws Exception {
        AudioEngine engine = engine();
        engine.setCrossfadeMs(5);
        short[] heard = playAll(engine, "odd.mp3", next("b.mp3", 0f));
        Tone b = files.get("b.mp3");
        assertEquals(List.of("advanced", "ended"), events);
        // b is heard to its very end: none of its frames were dropped while mixing
        int bStart = heard.length - b.frames();
        assertTrue(bStart > 3 * 1024 - 1024, "b started at " + bStart);
        assertEquals(b.value(), heard[heard.length - 1], 1);
    }
}
