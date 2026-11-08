package com.viby.core.playback;

import com.viby.core.settings.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Desktop EqFx: equalizer state (enabled, preset, 20 gains, preamp, user presets) persisted in
 * settings.json and applied to the audio engine's {@link Equalizer}.
 */
public final class EqualizerController {

    /** Preset name used once a band is edited by hand. */
    public static final String PRESET_CUSTOM = "";
    private static final String KEY = "equalizer";

    private final Equalizer equalizer;
    private final Settings settings;
    private boolean enabled;
    private String preset = EqPresets.NAMES[0];
    private float[] gains = new float[Equalizer.BANDS];
    private float preamp;
    private JSONObject customPresets;

    public EqualizerController(Equalizer equalizer, Settings settings) {
        this.equalizer = equalizer;
        this.settings = settings;
        reload();
    }

    /** Reads the saved state again, e.g. after a library backup restored the settings. */
    public synchronized void reload() {
        JSONObject saved = settings.getJson(KEY);
        enabled = saved.optBoolean("enabled", false);
        preset = saved.optString("preset", EqPresets.NAMES[0]);
        preamp = (float) saved.optDouble("preamp", 0);
        JSONArray savedGains = saved.optJSONArray("gains");
        gains = new float[Equalizer.BANDS];
        if (savedGains != null && savedGains.length() == Equalizer.BANDS) {
            for (int band = 0; band < Equalizer.BANDS; band++) {
                gains[band] = (float) savedGains.optDouble(band, 0);
            }
        }
        JSONObject custom = saved.optJSONObject("customPresets");
        customPresets = custom != null ? custom : new JSONObject();
        applyAll();
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized void setEnabled(boolean value) {
        enabled = value;
        equalizer.setEnabled(value);
        save();
    }

    public synchronized float[] gains() {
        return gains.clone();
    }

    public synchronized float gain(int band) {
        return gains[band];
    }

    public synchronized void setGain(int band, float gainDb) {
        setGain(band, gainDb, true);
    }

    /** @param persist false while a drag gesture is in progress; call {@link #commit()} after */
    public synchronized void setGain(int band, float gainDb, boolean persist) {
        gains[band] = clamp(gainDb);
        preset = PRESET_CUSTOM;
        equalizer.setGain(band, gains[band]);
        if (persist) {
            save();
        }
    }

    public synchronized void setPreamp(float gainDb, boolean persist) {
        preamp = clamp(gainDb);
        equalizer.setPreamp(preamp);
        if (persist) {
            save();
        }
    }

    public synchronized void commit() {
        save();
    }

    public synchronized float preamp() {
        return preamp;
    }

    public synchronized void setPreamp(float gainDb) {
        preamp = clamp(gainDb);
        equalizer.setPreamp(preamp);
        save();
    }

    /** Current preset name, or {@link #PRESET_CUSTOM} after manual edits. */
    public synchronized String preset() {
        return preset;
    }

    /** Built-in presets followed by user presets. */
    public synchronized List<String> presetNames() {
        List<String> names = new ArrayList<>(List.of(EqPresets.NAMES));
        for (String name : customPresets.keySet().stream().sorted().toList()) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    public synchronized boolean isCustomPreset(String name) {
        return customPresets.has(name) && !List.of(EqPresets.NAMES).contains(name);
    }

    public synchronized void applyPreset(String name) {
        JSONArray custom = customPresets.optJSONArray(name);
        if (custom != null && custom.length() == Equalizer.BANDS) {
            for (int band = 0; band < Equalizer.BANDS; band++) {
                gains[band] = clamp((float) custom.optDouble(band, 0));
            }
        } else {
            gains = EqPresets.curve(name);
        }
        preset = name;
        equalizer.setGains(gains);
        save();
    }

    public synchronized void saveCurrentAsPreset(String name) {
        String clean = name.trim();
        if (clean.isEmpty() || List.of(EqPresets.NAMES).contains(clean)) {
            throw new IllegalArgumentException("Имя пресета занято встроенным пресетом");
        }
        JSONArray curve = new JSONArray();
        for (float gain : gains) {
            curve.put(gain);
        }
        customPresets.put(clean, curve);
        preset = clean;
        save();
    }

    public synchronized void deletePreset(String name) {
        customPresets.remove(name);
        if (preset.equals(name)) {
            preset = PRESET_CUSTOM;
        }
        save();
    }

    private void applyAll() {
        equalizer.setGains(gains);
        equalizer.setPreamp(preamp);
        equalizer.setEnabled(enabled);
    }

    private void save() {
        JSONArray savedGains = new JSONArray();
        for (float gain : gains) {
            savedGains.put(gain);
        }
        settings.putJson(KEY, new JSONObject()
                .put("enabled", enabled)
                .put("preset", preset)
                .put("preamp", preamp)
                .put("gains", savedGains)
                .put("customPresets", customPresets));
    }

    private static float clamp(float gainDb) {
        return Math.max(-Equalizer.MAX_GAIN_DB, Math.min(Equalizer.MAX_GAIN_DB, gainDb));
    }
}
