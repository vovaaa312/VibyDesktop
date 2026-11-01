package com.viby.core.auth;

import com.viby.core.ytdlp.YtDlpRequest;

import org.jspecify.annotations.Nullable;
import org.json.JSONObject;

import java.nio.file.Path;
import java.util.List;

/**
 * Where yt-dlp takes YouTube account cookies from. Replaces the Android WebView login:
 * desktop browsers block Google sign-in inside embedded web views.
 */
public final class CookieSource {

    public enum Type { NONE, BROWSER, FILE }

    /** Browsers supported by {@code yt-dlp --cookies-from-browser}. */
    public static final List<String> BROWSERS = List.of(
            "firefox", "chrome", "edge", "brave", "chromium", "opera", "vivaldi", "safari", "whale");

    private static final CookieSource NONE = new CookieSource(Type.NONE, null, null, null);

    private final Type type;
    private final @Nullable String browser;
    private final @Nullable String profile;
    private final @Nullable Path file;

    private CookieSource(Type type, @Nullable String browser, @Nullable String profile,
                         @Nullable Path file) {
        this.type = type;
        this.browser = browser;
        this.profile = profile;
        this.file = file;
    }

    public static CookieSource none() {
        return NONE;
    }

    /** @param profile optional browser profile name or path */
    public static CookieSource browser(String browser, @Nullable String profile) {
        if (!BROWSERS.contains(browser)) {
            throw new IllegalArgumentException("Unsupported browser: " + browser);
        }
        String cleanProfile = profile == null || profile.isBlank() ? null : profile.trim();
        return new CookieSource(Type.BROWSER, browser, cleanProfile, null);
    }

    /** A Netscape-format cookies.txt exported by a browser extension. */
    public static CookieSource file(Path file) {
        return new CookieSource(Type.FILE, null, null, file);
    }

    public Type type() {
        return type;
    }

    public @Nullable String browser() {
        return browser;
    }

    public @Nullable String profile() {
        return profile;
    }

    public @Nullable Path file() {
        return file;
    }

    public boolean isConfigured() {
        return type != Type.NONE;
    }

    public void apply(YtDlpRequest request) {
        switch (type) {
            case BROWSER -> request.addOption("--cookies-from-browser",
                    profile != null ? browser + ":" + profile : browser);
            case FILE -> request.addOption("--cookies", String.valueOf(file));
            case NONE -> {
            }
        }
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject().put("type", type.name());
        if (browser != null) {
            json.put("browser", browser);
        }
        if (profile != null) {
            json.put("profile", profile);
        }
        if (file != null) {
            json.put("file", file.toString());
        }
        return json;
    }

    public static CookieSource fromJson(@Nullable JSONObject json) {
        if (json == null) {
            return NONE;
        }
        try {
            return switch (Type.valueOf(json.optString("type", "NONE"))) {
                case BROWSER -> browser(json.getString("browser"), json.optString("profile", null));
                case FILE -> file(Path.of(json.getString("file")));
                case NONE -> NONE;
            };
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CookieSource source && toJson().similar(source.toJson());
    }

    @Override
    public int hashCode() {
        return toJson().toString().hashCode();
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}
