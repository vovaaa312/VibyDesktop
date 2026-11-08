package com.viby.core.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class StorageHelper {

    private static final int MAX_NAME_LENGTH = 150;
    /** Room for yt-dlp's intermediate file, the converted audio and the database. */
    public static final long MIN_FREE_BYTES = 200L * 1024 * 1024;
    /** Upper bound for one track while it is downloaded and converted (~384 kbit/s). */
    private static final long BYTES_PER_SECOND = 48_000;

    private StorageHelper() {
    }

    /** Playlist folder: &lt;music directory&gt;/&lt;playlist&gt;/, created on demand. */
    public static File playlistDir(Path musicRoot, String playlistName) {
        File dir = musicRoot.resolve(sanitize(playlistName)).toFile();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** Убирает из имени папки символы, запрещённые в файловых системах. */
    public static String sanitize(String name) {
        String cleaned = name.trim().replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        // Windows rejects trailing dots/spaces and very long path segments
        cleaned = cleaned.replaceAll("[. ]+$", "");
        if (cleaned.length() > MAX_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_NAME_LENGTH).trim();
        }
        return cleaned.isEmpty() ? "Default" : cleaned;
    }

    /** Usable bytes on the volume of [path] (its nearest existing parent); -1 when unknown. */
    public static long freeBytes(Path path) {
        Path existing = path.toAbsolutePath();
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        try {
            return existing != null ? Files.getFileStore(existing).getUsableSpace() : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    /** Free space a download of [durationMs] needs before it starts. */
    public static long requiredBytes(long durationMs) {
        return MIN_FREE_BYTES + Math.max(0, durationMs) / 1000 * BYTES_PER_SECOND;
    }

    /**
     * Fails before yt-dlp starts instead of halfway through the file; the message is classified
     * as {@code STORAGE_FULL} ("Недостаточно свободного места").
     */
    public static void requireSpace(Path directory, long durationMs) throws IOException {
        long free = freeBytes(directory);
        long required = requiredBytes(durationMs);
        if (free >= 0 && free < required) {
            throw new IOException(String.format(Locale.ROOT,
                    "No space left on device: %d MB free, %d MB needed", free >> 20, required >> 20));
        }
    }
}
