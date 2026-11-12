package com.viby.core.data;

import com.viby.core.util.StorageHelper;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settings → Папка с музыкой: moves the library's files from the old music folder to the new
 * one, keeping the playlist folders, and points the tracks at the new files. Track paths are
 * absolute, so without a move the old files simply stay where they were.
 */
public final class LibraryMover {

    /**
     * What a folder change would involve.
     *
     * @param files         library files that still lie in the old folder
     * @param bytes         their total size
     * @param audioInTarget audio files already present in the new folder
     */
    public record Plan(int files, long bytes, int audioInTarget) {
    }

    /**
     * @param moved      files now in the new folder (moved, or found there already)
     * @param failed     files that could not be moved; their tracks keep the old path
     * @param firstError the first failure, for the details dialog
     */
    public record Result(int moved, int failed, long bytes, @Nullable String firstError) {
    }

    public interface Progress {
        void onProgress(int done, int total);
    }

    private final LibraryRepository library;

    public LibraryMover(LibraryRepository library) {
        this.library = library;
    }

    public Plan plan(Path from, Path to) throws IOException {
        int files = 0;
        long bytes = 0;
        for (Path file : filesUnder(root(from), root(to)).keySet()) {
            if (Files.isRegularFile(file)) {
                files++;
                bytes += Files.size(file);
            }
        }
        return new Plan(files, bytes, LibraryScanner.audioFiles(to).size());
    }

    /**
     * Moves every library file under {@code from} to the same relative place under {@code to}.
     * A file that cannot be moved (in use, no access) is skipped and reported; the database is
     * updated for everything that did move even when the run is interrupted.
     */
    public Result move(Path from, Path to, Progress progress) throws IOException {
        Path fromRoot = root(from);
        Path toRoot = root(to);
        if (fromRoot.equals(toRoot)) {
            return new Result(0, 0, 0, null);
        }
        Plan plan = plan(fromRoot, toRoot);
        if (!sameVolume(fromRoot, toRoot)) {
            long free = StorageHelper.freeBytes(toRoot);
            if (free >= 0 && free < plan.bytes() + StorageHelper.MIN_FREE_BYTES) {
                // classified as STORAGE_FULL ("Недостаточно свободного места")
                throw new IOException("No space left on device: " + free / (1024 * 1024) + " MB free, "
                        + (plan.bytes() + StorageHelper.MIN_FREE_BYTES) / (1024 * 1024) + " MB needed");
            }
        }
        int moved = 0;
        int failed = 0;
        long bytes = 0;
        String firstError = null;
        // a download that finishes during the run lands in the old folder: one more pass picks it up
        for (int pass = 0; pass < 2; pass++) {
            Map<Path, List<Track>> files = filesUnder(fromRoot, toRoot);
            if (files.isEmpty()) {
                break;
            }
            List<Track> changed = new ArrayList<>();
            Set<Path> folders = new LinkedHashSet<>();
            int done = 0;
            try {
                for (Map.Entry<Path, List<Track>> entry : files.entrySet()) {
                    Path source = entry.getKey();
                    Path target = toRoot.resolve(fromRoot.relativize(source));
                    try {
                        long size = Files.isRegularFile(source) ? Files.size(source) : -1;
                        if (size < 0) {
                            // already gone from the old folder: use the copy in the new one, if any
                            if (!Files.isRegularFile(target)) {
                                continue;
                            }
                        } else if (Files.isRegularFile(target) && Files.size(target) == size) {
                            // the same file was copied there by hand
                            Files.delete(source);
                        } else {
                            if (Files.exists(target)) {
                                target = unique(target);
                            }
                            Files.createDirectories(target.getParent());
                            moveFile(source, target);
                            bytes += size;
                        }
                        folders.add(source.getParent());
                        for (Track track : entry.getValue()) {
                            track.filePath = target.toString();
                            changed.add(track);
                        }
                        moved++;
                    } catch (IOException | RuntimeException e) {
                        // pass 1 retries nothing: a failure in pass 0 would fail again
                        if (pass == 0) {
                            failed++;
                            if (firstError == null) {
                                firstError = source + ": " + e;
                            }
                        }
                    } finally {
                        if (pass == 0) {
                            progress.onProgress(++done, files.size());
                        }
                    }
                }
            } finally {
                library.updateAll(changed);
            }
            folders.add(fromRoot);
            for (Path folder : folders) {
                deleteIfEmpty(folder);
            }
            if (failed > 0) {
                break;
            }
        }
        return new Result(moved, failed, bytes, firstError);
    }

    /**
     * Distinct files of library tracks under {@code root}, each with the tracks that share it.
     * Files already under {@code target} are left out: when the new folder lies inside the old
     * one (D:\Music → D:\Music\Viby) they are where they belong.
     */
    private Map<Path, List<Track>> filesUnder(Path root, Path target) {
        Map<Path, List<Track>> files = new LinkedHashMap<>();
        for (Track track : library.allTracks()) {
            if (track.filePath == null || track.filePath.isEmpty()) {
                continue;
            }
            Path file;
            try {
                file = Path.of(track.filePath).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                continue; // a path this system cannot represent (restored from another OS)
            }
            if (file.startsWith(root) && !file.equals(root) && !file.startsWith(target)) {
                files.computeIfAbsent(file, key -> new ArrayList<>()).add(track);
            }
        }
        return files;
    }

    /** Rename inside one volume; copy, verify and delete across volumes. */
    static void moveFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // ".part" so "Хранилище → Временные файлы" cleans up after a crash
            Path temp = target.resolveSibling(target.getFileName() + ".part");
            try {
                Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                if (Files.size(temp) != Files.size(source)) {
                    throw new IOException("Copy of " + source + " is incomplete");
                }
                Files.move(temp, target);
            } catch (IOException | RuntimeException copyError) {
                Files.deleteIfExists(temp);
                throw copyError;
            }
            try {
                Files.delete(source);
            } catch (IOException ignored) {
                // the copy is complete and the track uses it; the old file is only left behind
            }
        }
    }

    /** "Song.mp3" → "Song (2).mp3" when another file already has the name. */
    static Path unique(Path target) {
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            Path candidate = target.resolveSibling(base + " (" + i + ")" + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    private static void deleteIfEmpty(Path folder) {
        try (var entries = Files.list(folder)) {
            if (entries.findAny().isPresent()) {
                return;
            }
        } catch (IOException e) {
            return;
        }
        try {
            Files.delete(folder);
        } catch (IOException ignored) {
            // still in use: an empty folder is harmless
        }
    }

    private static boolean sameVolume(Path from, Path to) {
        try {
            Path existing = to;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            return existing != null && Files.exists(from)
                    && Files.getFileStore(from).equals(Files.getFileStore(existing));
        } catch (IOException e) {
            return false;
        }
    }

    private static Path root(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
