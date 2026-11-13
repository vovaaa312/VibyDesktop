package com.viby.core.settings;

import com.viby.core.auth.CookieSource;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** User preferences persisted as settings.json (desktop SharedPreferences). */
public final class Settings {

    public static final String FORMAT_MP3 = "mp3";
    public static final String FORMAT_M4A = "m4a";
    public static final String QUALITY_BEST = "0";
    public static final java.util.List<String> MP3_QUALITIES = java.util.List.of(QUALITY_BEST, "320K", "192K", "128K");

    private final Path file;
    private final Path defaultMusicDirectory;
    private JSONObject values;

    public Settings(Path file, Path defaultMusicDirectory) {
        this.file = file;
        this.defaultMusicDirectory = defaultMusicDirectory;
        this.values = load(file);
    }

    public synchronized CookieSource cookieSource() {
        return CookieSource.fromJson(values.optJSONObject("cookies"));
    }

    public synchronized void setCookieSource(CookieSource source) {
        values.put("cookies", source.toJson());
        save();
    }

    public synchronized Path musicDirectory() {
        String path = values.optString("musicDirectory", "");
        return path.isBlank() ? defaultMusicDirectory : Path.of(path);
    }

    public synchronized void setMusicDirectory(Path directory) {
        values.put("musicDirectory", directory.toString());
        save();
    }

    /** Preferred audio format; Android uses mp3 and falls back to m4a. */
    public synchronized String audioFormat() {
        String format = values.optString("audioFormat", FORMAT_MP3);
        return FORMAT_M4A.equals(format) ? FORMAT_M4A : FORMAT_MP3;
    }

    public synchronized void setAudioFormat(String format) {
        values.put("audioFormat", format);
        save();
    }

    /**
     * yt-dlp --audio-quality for MP3: "0" is the best variable bitrate (~245 kbit/s), the rest
     * are constant bitrates. M4A keeps the source stream and has no such choice.
     */
    public synchronized String audioQuality() {
        String quality = values.optString("audioQuality", QUALITY_BEST);
        return MP3_QUALITIES.contains(quality) ? quality : QUALITY_BEST;
    }

    public synchronized void setAudioQuality(String quality) {
        values.put("audioQuality", quality);
        save();
    }

    /** Volume normalization (loudness measured per track); on by default, like Spotify. */
    public synchronized boolean normalizeVolume() {
        return values.optBoolean("normalizeVolume", true);
    }

    public synchronized void setNormalizeVolume(boolean enabled) {
        values.put("normalizeVolume", enabled);
        save();
    }

    /** The window close button hides Viby to the tray instead of quitting (music keeps playing). */
    public synchronized boolean closeToTray() {
        return values.optBoolean("closeToTray", false);
    }

    public synchronized void setCloseToTray(boolean enabled) {
        values.put("closeToTray", enabled);
        save();
    }

    /** Longest crossfade offered: a longer overlap starts too noticeably early. */
    public static final int MAX_CROSSFADE_SECONDS = 3;

    /** Crossfade between tracks in seconds (0, 1 or 3); 0 = gapless without overlap. */
    public synchronized int crossfadeSeconds() {
        // 6 and 10 s were offered at first
        return Math.max(0, Math.min(MAX_CROSSFADE_SECONDS, values.optInt("crossfadeSeconds", 0)));
    }

    public synchronized void setCrossfadeSeconds(int seconds) {
        values.put("crossfadeSeconds", seconds);
        save();
    }

    public synchronized @org.jspecify.annotations.Nullable Object raw(String key) {
        return values.opt(key);
    }

    public synchronized void putRaw(String key, Object value) {
        values.put(key, value);
        save();
    }

    public synchronized JSONObject getJson(String key) {
        JSONObject value = values.optJSONObject(key);
        return value != null ? new JSONObject(value.toString()) : new JSONObject();
    }

    public synchronized void putJson(String key, JSONObject value) {
        values.put(key, value);
        save();
    }

    public synchronized String getString(String key, String fallback) {
        return values.optString(key, fallback);
    }

    public synchronized void putString(String key, String value) {
        values.put(key, value);
        save();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, values.toString(2), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save settings", e);
        }
    }

    private static JSONObject load(Path file) {
        try {
            return Files.isRegularFile(file)
                    ? new JSONObject(Files.readString(file, StandardCharsets.UTF_8))
                    : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
