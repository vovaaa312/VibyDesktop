package com.viby.core.playback;

/**
 * 20-band peaking equalizer with preamp on the {@link EqPresets#FREQS_HZ} grid. Replaces
 * Android DynamicsProcessing: a cascade of RBJ peaking biquads per channel.
 * Setters may be called from any thread; {@link #process} runs on the audio thread.
 */
public final class Equalizer {

    public static final int BANDS = EqPresets.FREQS_HZ.length;
    public static final float MAX_GAIN_DB = 15f;
    /** Bands are ~half an octave apart; Q 2 keeps neighbours overlapping smoothly. */
    private static final double Q = 2.0;

    /** Immutable coefficient set, swapped atomically when settings change. */
    private record Coefficients(boolean active, float preamp, double[][] bands) {
    }

    private final int sampleRate;
    private final int channels;
    private final float[] gainsDb = new float[BANDS];
    private float preampDb;
    private boolean enabled;
    private volatile Coefficients coefficients;
    /** Transposed direct form II state: [band][channel][z1, z2]. */
    private final double[][][] state;

    public Equalizer(int sampleRate, int channels) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.state = new double[BANDS][channels][2];
        this.coefficients = build();
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        coefficients = build();
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized void setGain(int band, float gainDb) {
        gainsDb[band] = clamp(gainDb);
        coefficients = build();
    }

    public synchronized void setGains(float[] curve) {
        for (int band = 0; band < BANDS && band < curve.length; band++) {
            gainsDb[band] = clamp(curve[band]);
        }
        coefficients = build();
    }

    public synchronized float[] gains() {
        return gainsDb.clone();
    }

    public synchronized void setPreamp(float gainDb) {
        preampDb = clamp(gainDb);
        coefficients = build();
    }

    public synchronized float preamp() {
        return preampDb;
    }

    /** Filters interleaved samples in place; samples are in [-1, 1]. */
    public void process(float[] samples, int count) {
        Coefficients current = coefficients;
        if (!current.active()) {
            return;
        }
        for (int i = 0; i < count; i++) {
            int channel = i % channels;
            double value = samples[i] * current.preamp();
            for (int band = 0; band < BANDS; band++) {
                double[] c = current.bands()[band];
                if (c == null) {
                    continue;
                }
                double[] z = state[band][channel];
                double out = c[0] * value + z[0];
                z[0] = c[1] * value - c[3] * out + z[1];
                z[1] = c[2] * value - c[4] * out;
                value = out;
            }
            samples[i] = (float) value;
        }
    }

    private Coefficients build() {
        boolean anyBand = false;
        double[][] bands = new double[BANDS][];
        for (int band = 0; band < BANDS; band++) {
            if (Math.abs(gainsDb[band]) < 0.05f) {
                continue;
            }
            anyBand = true;
            bands[band] = peaking(Math.min(EqPresets.FREQS_HZ[band], sampleRate * 0.45), gainsDb[band]);
        }
        boolean active = enabled && (anyBand || Math.abs(preampDb) >= 0.05f);
        return new Coefficients(active, (float) Math.pow(10, preampDb / 20.0), bands);
    }

    /** RBJ audio EQ cookbook peaking filter, normalized: {b0, b1, b2, a1, a2}. */
    private double[] peaking(double frequency, double gainDb) {
        double a = Math.pow(10, gainDb / 40.0);
        double w0 = 2 * Math.PI * frequency / sampleRate;
        double alpha = Math.sin(w0) / (2 * Q);
        double cos = Math.cos(w0);
        double a0 = 1 + alpha / a;
        return new double[]{
                (1 + alpha * a) / a0,
                -2 * cos / a0,
                (1 - alpha * a) / a0,
                -2 * cos / a0,
                (1 - alpha / a) / a0,
        };
    }

    private static float clamp(float gainDb) {
        return Math.max(-MAX_GAIN_DB, Math.min(MAX_GAIN_DB, gainDb));
    }
}
