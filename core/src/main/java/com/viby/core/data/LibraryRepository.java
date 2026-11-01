package com.viby.core.data;

import org.jspecify.annotations.Nullable;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Playlist library on top of the DAOs. All mutations notify listeners so the UI can reload.
 * Methods are blocking; call them off the UI thread.
 */
public final class LibraryRepository {

    public interface Listener {
        void onLibraryChanged();
    }

    public record PlaylistSummary(String name, int trackCount, long durationMs, long createdAt,
                                  @Nullable String coverUrl, @Nullable String sourceUrl) {
    }

    /** Unchecked wrapper so Kotlin callers and lambdas are not burdened with SQLException. */
    public static final class LibraryException extends RuntimeException {
        LibraryException(SQLException cause) {
            super(cause.getMessage(), cause);
        }
    }

    private final TrackDao tracks;
    private final PlaylistDao playlists;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public LibraryRepository(Database database) {
        this.tracks = new TrackDao(database);
        this.playlists = new PlaylistDao(database);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void notifyChanged() {
        listeners.forEach(Listener::onLibraryChanged);
    }

    public List<PlaylistSummary> playlists() {
        return call(() -> {
            Map<String, Long> created = new LinkedHashMap<>(playlists.all());
            for (String name : tracks.playlistNames()) {
                created.putIfAbsent(name, 0L);
            }
            Map<String, List<Track>> byPlaylist = new LinkedHashMap<>();
            for (Track track : tracks.all()) {
                byPlaylist.computeIfAbsent(track.playlistName, key -> new ArrayList<>()).add(track);
            }
            List<PlaylistSummary> result = new ArrayList<>();
            for (Map.Entry<String, Long> entry : created.entrySet()) {
                List<Track> items = byPlaylist.getOrDefault(entry.getKey(), List.of());
                PlaylistSource source = playlists.source(entry.getKey());
                result.add(new PlaylistSummary(entry.getKey(), items.size(),
                        items.stream().mapToLong(t -> t.durationMs).sum(), entry.getValue(),
                        items.isEmpty() ? null : items.get(0).thumbnailUrl,
                        source != null ? source.sourceUrl : null));
            }
            return result;
        });
    }

    public List<Track> tracks(String playlistName) {
        return call(() -> tracks.byPlaylist(playlistName));
    }

    public @Nullable Track track(long id) {
        return call(() -> tracks.byId(id));
    }

    /** @return false when a playlist with this name already exists */
    public boolean createPlaylist(String name) {
        boolean created = call(() -> !exists(name) && playlists.insert(name, System.currentTimeMillis()));
        if (created) {
            notifyChanged();
        }
        return created;
    }

    /** Ensures a playlist row exists, e.g. when a download names a new playlist. */
    public void ensurePlaylist(String name) {
        if (call(() -> playlists.insert(name, System.currentTimeMillis()))) {
            notifyChanged();
        }
    }

    /** Files stay where they are; tracks keep absolute paths. */
    public boolean renamePlaylist(String from, String to) {
        if (from.equals(to)) {
            return true;
        }
        boolean renamed = call(() -> {
            if (exists(to)) {
                return false;
            }
            playlists.insert(from, System.currentTimeMillis());
            playlists.rename(from, to);
            tracks.renamePlaylist(from, to);
            return true;
        });
        if (renamed) {
            notifyChanged();
        }
        return renamed;
    }

    /** Removes the playlist and deletes audio files no other playlist uses. */
    public void deletePlaylist(String name) {
        run(() -> {
            List<Track> items = tracks.byPlaylist(name);
            tracks.deleteByPlaylist(name);
            playlists.delete(name);
            for (Track track : items) {
                deleteFileIfUnused(track.filePath);
            }
        });
        notifyChanged();
    }

    public void removeTracks(Collection<Long> ids) {
        run(() -> {
            for (Track track : tracks.byIds(ids)) {
                tracks.delete(track.id);
                deleteFileIfUnused(track.filePath);
            }
        });
        notifyChanged();
    }

    public void moveTracks(Collection<Long> ids, String targetPlaylist) {
        run(() -> {
            playlists.insert(targetPlaylist, System.currentTimeMillis());
            int position = tracks.nextPosition(targetPlaylist);
            for (Track track : tracks.byIds(ids)) {
                if (track.playlistName.equals(targetPlaylist)) {
                    continue;
                }
                track.playlistName = targetPlaylist;
                track.position = position++;
                tracks.update(track);
            }
        });
        notifyChanged();
    }

    // ---- download pipeline ----

    public @Nullable Track findInPlaylist(String playlistName, String videoId) {
        return call(() -> tracks.findInPlaylist(playlistName, videoId));
    }

    /** A downloaded copy of the video whose file still exists. */
    public @Nullable Track findReusableDownload(String videoId) {
        return call(() -> {
            for (Track track : tracks.downloadedByVideoId(videoId)) {
                if (new File(track.filePath).isFile()) {
                    return track;
                }
            }
            return null;
        });
    }

    public Track insert(Track track) {
        run(() -> {
            if (track.position == 0) {
                track.position = tracks.nextPosition(track.playlistName);
            }
            tracks.insert(track);
        });
        notifyChanged();
        return track;
    }

    public void update(Track track) {
        run(() -> tracks.update(track));
        notifyChanged();
    }

    /** Frequent progress writes do not notify; the download manager publishes progress itself. */
    public void updateProgress(long id, String state, int progress) {
        run(() -> tracks.updateProgress(id, state, progress));
    }

    public void delete(long id) {
        run(() -> tracks.delete(id));
        notifyChanged();
    }

    public void saveSource(PlaylistSource source) {
        run(() -> playlists.upsertSource(source));
    }

    public void resetInterruptedDownloads() {
        run(tracks::resetInterruptedDownloads);
    }

    private boolean exists(String name) throws SQLException {
        return playlists.all().containsKey(name) || tracks.playlistNames().contains(name);
    }

    private void deleteFileIfUnused(@Nullable String filePath) throws SQLException {
        if (filePath == null || filePath.isEmpty() || tracks.countByFilePath(filePath) > 0) {
            return;
        }
        File file = new File(filePath);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        File directory = file.getParentFile();
        String[] rest = directory != null ? directory.list() : null;
        if (rest != null && rest.length == 0) {
            //noinspection ResultOfMethodCallIgnored
            directory.delete();
        }
    }

    private interface SqlCall<T> {
        T call() throws SQLException;
    }

    private interface SqlRun {
        void run() throws SQLException;
    }

    private static <T> T call(SqlCall<T> call) {
        try {
            return call.call();
        } catch (SQLException e) {
            throw new LibraryException(e);
        }
    }

    private static void run(SqlRun run) {
        call(() -> {
            run.run();
            return null;
        });
    }
}
