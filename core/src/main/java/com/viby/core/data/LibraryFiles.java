package com.viby.core.data;

import com.viby.core.util.StorageHelper;

import org.jspecify.annotations.Nullable;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Android's {@code LibraryFileController}: imports local audio files into a playlist folder and
 * holds the rules for files shared between playlist entries.
 */
public final class LibraryFiles {

    private static final Logger LOG = Logger.getLogger(LibraryFiles.class.getName());
    private static final long PROBE_TIMEOUT_SECONDS = 15;

    private final RoomTrackDao dao;
    private final Supplier<Path> musicDirectory;
    private final Supplier<Path> ffprobe;

    public LibraryFiles(RoomTrackDao dao, Supplier<Path> musicDirectory, Supplier<Path> ffprobe) {
        this.dao = dao;
        this.musicDirectory = musicDirectory;
        this.ffprobe = ffprobe;
    }

    /** Copies the files into the playlist folder and adds them to its end; returns how many. */
    public int importFiles(List<Path> files, String playlist) {
        File directory = StorageHelper.playlistDir(musicDirectory.get(), playlist);
        int added = 0;
        for (Path file : files) {
            if (importFile(directory, file, playlist)) {
                added++;
            }
        }
        return added;
    }

    private boolean importFile(File directory, Path source, String playlist) {
        File destination = null;
        try {
            destination = uniqueDestination(directory,
                    StorageHelper.sanitize(source.getFileName().toString()), System.currentTimeMillis());
            Files.copy(source, destination.toPath(), StandardCopyOption.COPY_ATTRIBUTES);

            MediaInfo metadata = readMetadata(destination);
            Track track = new Track();
            track.title = hasText(metadata.title)
                    ? metadata.title : stripExtension(destination.getName());
            track.uploader = metadata.artist;
            track.durationMs = metadata.durationMs;
            track.filePath = destination.getAbsolutePath();
            track.playlistName = playlist;
            track.position = dao.nextPosition(playlist);
            track.createdAt = System.currentTimeMillis();
            dao.insert(track);
            return true;
        } catch (Exception error) {
            LOG.log(Level.WARNING, "Import failed: " + source, error);
            if (destination != null) {
                //noinspection ResultOfMethodCallIgnored
                destination.delete();
            }
            return false;
        }
    }

    /** ffprobe in place of Android's MediaMetadataRetriever; empty info when it fails. */
    private MediaInfo readMetadata(File file) {
        try {
            Process probe = new ProcessBuilder(ffprobe.get().toString(), "-v", "error",
                    "-show_entries", "format=duration:format_tags=title,artist",
                    "-of", "json", file.getAbsolutePath())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!probe.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return MediaInfo.EMPTY;
            }
            JSONObject format = new JSONObject(output).optJSONObject("format");
            if (format == null) {
                return MediaInfo.EMPTY;
            }
            JSONObject tags = format.optJSONObject("tags");
            return new MediaInfo(tag(tags, "title"), tag(tags, "artist"),
                    parseSeconds(format.optString("duration", null)));
        } catch (IOException | RuntimeException error) {
            return MediaInfo.EMPTY;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return MediaInfo.EMPTY;
        }
    }

    /** Tag keys are upper case in some containers (TITLE in Vorbis comments). */
    private static @Nullable String tag(@Nullable JSONObject tags, String key) {
        if (tags == null) {
            return null;
        }
        for (String name : tags.keySet()) {
            if (name.equalsIgnoreCase(key)) {
                return tags.optString(name, null);
            }
        }
        return null;
    }

    static File uniqueDestination(File directory, String name, long suffix) {
        File destination = new File(directory, name);
        return destination.exists()
                ? new File(directory, suffix + " " + destination.getName())
                : destination;
    }

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static long parseDuration(@Nullable String duration) {
        if (duration == null) {
            return 0L;
        }
        try {
            return Long.parseLong(duration);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /** ffprobe reports seconds with a fraction ("215.433000"). */
    static long parseSeconds(@Nullable String seconds) {
        if (seconds == null) {
            return 0L;
        }
        try {
            return Math.round(Double.parseDouble(seconds) * 1000);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    static boolean shouldDeleteFile(@Nullable String filePath, int references) {
        return filePath != null && !filePath.isEmpty() && references == 0;
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isEmpty();
    }

    private record MediaInfo(@Nullable String title, @Nullable String artist, long durationMs) {
        static final MediaInfo EMPTY = new MediaInfo(null, null, 0L);
    }
}
