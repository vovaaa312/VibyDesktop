package com.viby.core.util;

import java.io.File;
import java.nio.file.Path;

public final class StorageHelper {

    private static final int MAX_NAME_LENGTH = 150;

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
}
