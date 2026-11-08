package com.viby.core.data;

import com.viby.core.util.StorageHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** What the music folder holds: playlist sizes, files no track uses, yt-dlp leftovers, free space. */
public final class LibraryStorage {

    public record PlaylistUsage(String name, int files, long bytes) {
    }

    /**
     * @param libraryBytes distinct files referenced by the library (shared files count once)
     * @param missingFiles tracks marked downloaded whose file is gone
     * @param unreferenced audio files in the music folder that no track uses
     * @param temp         partial downloads and other yt-dlp leftovers
     */
    public record Report(Path musicDirectory, long freeBytes, long libraryBytes, int libraryFiles,
                         List<PlaylistUsage> playlists, int missingFiles,
                         List<Path> unreferenced, long unreferencedBytes,
                         List<Path> temp, long tempBytes) {
    }

    private final LibraryRepository library;
    private final Supplier<Path> musicDirectory;

    public LibraryStorage(LibraryRepository library, Supplier<Path> musicDirectory) {
        this.library = library;
        this.musicDirectory = musicDirectory;
    }

    public Report report() throws IOException {
        Path root = musicDirectory.get();
        Map<String, Long> sizes = new HashMap<>();
        Map<String, Set<String>> playlistFiles = new LinkedHashMap<>();
        for (String name : library.playlistCreatedAt().keySet()) {
            playlistFiles.put(name, new HashSet<>());
        }
        int missing = 0;
        for (Track track : library.allTracks()) {
            if (!track.downloaded || track.filePath == null || track.filePath.isEmpty()) {
                continue;
            }
            File file = new File(track.filePath);
            if (!file.isFile()) {
                missing++;
                continue;
            }
            String key = LibraryScanner.key(file.toPath());
            sizes.putIfAbsent(key, file.length());
            playlistFiles.computeIfAbsent(track.playlistName, n -> new HashSet<>()).add(key);
        }
        List<PlaylistUsage> playlists = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : playlistFiles.entrySet()) {
            long bytes = entry.getValue().stream().mapToLong(sizes::get).sum();
            playlists.add(new PlaylistUsage(entry.getKey(), entry.getValue().size(), bytes));
        }
        playlists.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));

        List<Path> unreferenced = new ArrayList<>();
        List<Path> temp = new ArrayList<>();
        long unreferencedBytes = 0;
        long tempBytes = 0;
        for (Path file : LibraryScanner.files(root)) {
            String name = file.getFileName().toString();
            if (LibraryScanner.isTemp(name)) {
                temp.add(file);
                tempBytes += size(file);
            } else if (LibraryScanner.isAudio(name) && !sizes.containsKey(LibraryScanner.key(file))) {
                unreferenced.add(file);
                unreferencedBytes += size(file);
            }
        }
        long libraryBytes = sizes.values().stream().mapToLong(Long::longValue).sum();
        return new Report(root, StorageHelper.freeBytes(root), libraryBytes, sizes.size(), playlists,
                missing, unreferenced, unreferencedBytes, temp, tempBytes);
    }

    /**
     * Deletes yt-dlp leftovers except in the folders of [busyPlaylists], whose paused or running
     * downloads resume from their .part files.
     *
     * @return freed bytes
     */
    public long deleteTemp(Collection<String> busyPlaylists) throws IOException {
        Set<String> busy = new HashSet<>();
        for (String playlist : busyPlaylists) {
            busy.add(StorageHelper.sanitize(playlist).toLowerCase(Locale.ROOT));
        }
        Path root = musicDirectory.get();
        long freed = 0;
        for (Path file : report().temp()) {
            Path parent = file.getParent();
            String folder = parent != null && !parent.equals(root) && parent.getFileName() != null
                    ? parent.getFileName().toString().toLowerCase(Locale.ROOT) : "";
            if (busy.contains(folder)) {
                continue;
            }
            long bytes = size(file);
            if (Files.deleteIfExists(file)) {
                freed += bytes;
            }
        }
        return freed;
    }

    /** Deletes audio files that no track uses (after the user has seen them in the report). */
    public long deleteUnreferenced() throws IOException {
        long freed = 0;
        for (Path file : report().unreferenced()) {
            long bytes = size(file);
            if (Files.deleteIfExists(file)) {
                freed += bytes;
            }
        }
        return freed;
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }
}
