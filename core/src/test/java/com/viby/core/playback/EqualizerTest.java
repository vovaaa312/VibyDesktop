package com.viby.core.playback;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

class EqualizerTest {

    private static final int RATE = 48_000;

    @Test
    void disabledOrFlatEqualizerPassesSamplesThrough() {
        float[] samples = sine(1000, 0.5f);
        float[] original = samples.clone();
        Equalizer equalizer = new Equalizer(RATE, 2);
        equalizer.setGain(10, 6);
        equalizer.process(samples, samples.length);
        assertArrayEquals(original, samples);

        equalizer.setEnabled(true);
        equalizer.setGain(10, 0);
        equalizer.process(samples, samples.length);
        assertArrayEquals(original, samples);
    }

    @Test
    void boostRaisesOnlyTheTargetedBand() {
        Equalizer equalizer = new Equalizer(RATE, 2);
        equalizer.setEnabled(true);
        equalizer.setGain(10, 12); // 1 kHz
        assertEquals(4.0, gainAt(equalizer, 1000), 0.4);   // +12 dB ~ x4
        assertEquals(1.0, gainAt(equalizer, 63), 0.05);
    }

    @Test
    void preampAndClampingFollowAndroidLimits() {
        Equalizer equalizer = new Equalizer(RATE, 2);
        equalizer.setEnabled(true);
        equalizer.setPreamp(-6);
        assertEquals(0.5, gainAt(equalizer, 1000), 0.02);
        equalizer.setGain(0, 40);
        assertEquals(Equalizer.MAX_GAIN_DB, equalizer.gains()[0]);
        equalizer.setGains(EqPresets.curve("Rock"));
        assertEquals(Equalizer.BANDS, equalizer.gains().length);
    }

    @Test
    void decoderCommandSeeksAndReconnectsStreams() {
        List<String> local = AudioEngine.command(Path.of("ffmpeg"), "a.mp3", 0);
        assertTrue(!local.contains("-ss") && !local.contains("-reconnect"));
        assertEquals("pipe:1", local.get(local.size() - 1));
        List<String> stream = AudioEngine.command(Path.of("ffmpeg"), "https://x/audio", 61_500);
        assertEquals("61.500", stream.get(stream.indexOf("-ss") + 1));
        assertTrue(stream.indexOf("-reconnect") < stream.indexOf("-i"));
    }

    /** Steady-state amplitude ratio for a sine at {@code frequency}. */
    private static double gainAt(Equalizer equalizer, double frequency) {
        float[] samples = sine(frequency, 0.1f);
        equalizer.process(samples, samples.length);
        return peak(samples, samples.length / 2) / 0.1;
    }

    private static float[] sine(double frequency, float amplitude) {
        float[] samples = new float[RATE * 2]; // 1 s stereo
        for (int frame = 0; frame < RATE; frame++) {
            float value = (float) (amplitude * Math.sin(2 * Math.PI * frequency * frame / RATE));
            samples[2 * frame] = value;
            samples[2 * frame + 1] = value;
        }
        return samples;
    }

    private static double peak(float[] samples, int from) {
        double max = 0;
        for (int i = from; i < samples.length; i++) {
            max = Math.max(max, Math.abs(samples[i]));
        }
        return max;
    }
}
