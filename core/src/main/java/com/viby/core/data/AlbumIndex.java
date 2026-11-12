package com.viby.core.data;

import org.json.JSONObject;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Album of every library file, for the "Альбомы" section. The database (Room v7, shared with
 * Android) has no album column, so the tag is read from the files (yt-dlp --embed-metadata
 * writes it for YouTube Music tracks) and cached: a file is probed once and again only when its
 * size or modification time changes.
 */
public final class AlbumIndex {

    public interface Progress {
        void onProgress(int done, int total);
    }

    private record Entry(long modified, long size, String album) {
    }

    private static final int SAVE_EVERY = 50;

    private final Path cacheFile;
    private final AudioProbe.Reader probe;
    private final Map<String, Entry> entries = new HashMap<>();

    public AlbumIndex(Path cacheFile, AudioProbe.Reader probe) {
        this.cacheFile = cacheFile;
        this.probe = probe;
        load();
    }

    /** File path → album, for the files that have one. */
    public synchronized Map<String, String> albums() {
        Map<String, String> albums = new HashMap<>();
        entries.forEach((path, entry) -> {
            if (!entry.album().isEmpty()) {
                albums.put(path, entry.album());
            }
        });
        return albums;
    }

    /**
     * Reads the album of the files that are new or changed and forgets files that left the
     * library. Returns how many files were read; 0 means {@link #albums()} did not change.
     */
    public int refresh(Collection<String> files, Progress progress) {
        Set<String> wanted = new HashSet<>(files);
        Map<String, long[]> stale = new HashMap<>();
        synchronized (this) {
            for (String path : wanted) {
                File file = new File(path);
                if (!file.isFile()) {
                    continue;
                }
                Entry known = entries.get(path);
                long modified = file.lastModified();
                long size = file.length();
                if (known == null || known.modified() != modified || known.size() != size) {
                    stale.put(path, new long[] {modified, size});
                }
            }
        }
        int done = 0;
        for (Map.Entry<String, long[]> item : stale.entrySet()) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            // outside the lock: ffprobe takes tens of milliseconds per file
            String album = probe.read(new File(item.getKey())).album();
            synchronized (this) {
                entries.put(item.getKey(), new Entry(item.getValue()[0], item.getValue()[1],
                        album == null ? "" : album.trim()));
                if (++done % SAVE_EVERY == 0) {
                    save();
                }
            }
            progress.onProgress(done, stale.size());
        }
        synchronized (this) {
            boolean pruned = entries.keySet().retainAll(wanted);
            if (done > 0 || pruned) {
                save();
            }
        }
        return done;
    }

    private void load() {
        try {
            if (!Files.isRegularFile(cacheFile)) {
                return;
            }
            JSONObject root = new JSONObject(Files.readString(cacheFile, StandardCharsets.UTF_8));
            for (String path : root.keySet()) {
                JSONObject item = root.optJSONObject(path);
                if (item != null) {
                    entries.put(path, new Entry(item.optLong("m"), item.optLong("s"), item.optString("a", "")));
                }
            }
        } catch (IOException | RuntimeException e) {
            entries.clear(); // a broken cache is simply rebuilt
        }
    }

    private void save() {
        JSONObject root = new JSONObject();
        entries.forEach((path, entry) -> root.put(path,
                new JSONObject().put("m", entry.modified()).put("s", entry.size()).put("a", entry.album())));
        try {
            Path temp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.createDirectories(cacheFile.toAbsolutePath().getParent());
            Files.writeString(temp, root.toString(), StandardCharsets.UTF_8);
            Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // the index still works from memory; it is rebuilt next time
        }
    }

    /** For tests. */
    synchronized @Nullable String albumOf(String path) {
        Entry entry = entries.get(path);
        return entry == null || entry.album().isEmpty() ? null : entry.album();
    }
}
