package com.viby.core.data;

import com.viby.core.util.StorageHelper;

import org.jspecify.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Android's {@code LibraryFileController}: imports local audio files into a playlist folder and
 * holds the rules for files shared between playlist entries.
 */
public final class LibraryFiles {

    private static final Logger LOG = Logger.getLogger(LibraryFiles.class.getName());

    private final RoomTrackDao dao;
    private final Supplier<Path> musicDirectory;
    private final AudioProbe.Reader probe;

    public LibraryFiles(RoomTrackDao dao, Supplier<Path> musicDirectory, Supplier<Path> ffprobe) {
        this.dao = dao;
        this.musicDirectory = musicDirectory;
        this.probe = AudioProbe.ffprobe(ffprobe);
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

            AudioProbe.Info metadata = probe.read(destination);
            Track track = new Track();
            track.title = hasText(metadata.title())
                    ? metadata.title() : stripExtension(destination.getName());
            track.uploader = metadata.artist();
            track.durationMs = metadata.durationMs();
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
}
