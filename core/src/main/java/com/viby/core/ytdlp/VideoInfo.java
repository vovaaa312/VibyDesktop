package com.viby.core.ytdlp;

import org.jspecify.annotations.Nullable;
import org.json.JSONObject;

/** Typed view over the JSON printed by {@code yt-dlp --dump-json}. */
public final class VideoInfo {

    private final JSONObject json;

    public VideoInfo(JSONObject json) {
        this.json = json;
    }

    public JSONObject json() {
        return json;
    }

    public @Nullable String getId() {
        return string("id");
    }

    public @Nullable String getTitle() {
        return string("title");
    }

    /** Track artist when YouTube Music provides one, otherwise the channel name. */
    public @Nullable String getArtist() {
        String artist = string("artist");
        if (artist == null) {
            artist = string("creator");
        }
        return artist != null ? artist : getUploader();
    }

    public @Nullable String getUploader() {
        String uploader = string("uploader");
        return uploader != null ? uploader : string("channel");
    }

    /** Duration in seconds; 0 when unknown (live streams). */
    public long getDuration() {
        return Math.round(json.optDouble("duration", 0));
    }

    public @Nullable String getThumbnail() {
        return string("thumbnail");
    }

    /** Direct media URL of the selected format; short-lived. */
    public @Nullable String getUrl() {
        return string("url");
    }

    public @Nullable String getWebpageUrl() {
        return string("webpage_url");
    }

    public @Nullable String getExt() {
        return string("ext");
    }

    private @Nullable String string(String key) {
        if (json.isNull(key)) {
            return null;
        }
        String value = json.optString(key, "").trim();
        return value.isEmpty() ? null : value;
    }
}
