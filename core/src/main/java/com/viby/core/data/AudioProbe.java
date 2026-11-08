package com.viby.core.data;

import com.viby.core.util.YoutubeUrlParser;

import org.jspecify.annotations.Nullable;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Tags of an audio file through ffprobe (Android: MediaMetadataRetriever). Downloads carry the
 * YouTube page in "purl"/"comment" (yt-dlp --embed-metadata), which identifies the video again
 * after the library database is lost.
 */
public final class AudioProbe {

    private static final long TIMEOUT_SECONDS = 15;

    public record Info(@Nullable String title, @Nullable String artist, long durationMs,
                       @Nullable String videoId) {
        public static final Info EMPTY = new Info(null, null, 0L, null);
    }

    public interface Reader {
        /** Never throws; {@link Info#EMPTY} when the file cannot be read. */
        Info read(File file);
    }

    private AudioProbe() {
    }

    public static Reader ffprobe(Supplier<Path> ffprobe) {
        return file -> {
            try {
                Process probe = new ProcessBuilder(ffprobe.get().toString(), "-v", "error",
                        "-show_entries", "format=duration:format_tags=title,artist,purl,comment",
                        "-of", "json", file.getAbsolutePath())
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!probe.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    probe.destroyForcibly();
                    return Info.EMPTY;
                }
                return parse(output);
            } catch (IOException | RuntimeException error) {
                return Info.EMPTY;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return Info.EMPTY;
            }
        };
    }

    /** ffprobe {@code -of json} output. */
    static Info parse(String json) {
        JSONObject format = new JSONObject(json).optJSONObject("format");
        if (format == null) {
            return Info.EMPTY;
        }
        JSONObject tags = format.optJSONObject("tags");
        String page = tag(tags, "purl");
        String videoId = page != null ? YoutubeUrlParser.videoId(page) : null;
        if (videoId == null) {
            String comment = tag(tags, "comment");
            videoId = comment != null ? YoutubeUrlParser.videoId(comment.trim()) : null;
        }
        return new Info(tag(tags, "title"), tag(tags, "artist"),
                LibraryFiles.parseSeconds(format.optString("duration", null)), videoId);
    }

    /** Tag keys are upper case in some containers (TITLE in Vorbis comments). */
    private static @Nullable String tag(@Nullable JSONObject tags, String key) {
        if (tags == null) {
            return null;
        }
        for (String name : tags.keySet()) {
            if (name.equalsIgnoreCase(key)) {
                String value = tags.optString(name, null);
                return value == null || value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
