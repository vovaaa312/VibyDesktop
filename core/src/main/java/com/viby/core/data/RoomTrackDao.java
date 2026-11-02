package com.viby.core.data;

import org.jspecify.annotations.Nullable;

import java.sql.SQLException;
import java.util.List;

/**
 * The Android Room {@code TrackDao} interface on top of the desktop JDBC DAO: same method names,
 * same queries and unchecked errors, so code ported from Android keeps its calls unchanged.
 */
public final class RoomTrackDao {

    private final Database database;
    private final TrackDao tracks;
    private final Runnable onChange;

    public RoomTrackDao(Database database) {
        this(database, () -> { });
    }

    /** @param onChange runs after every write, like Room invalidating LiveData observers */
    public RoomTrackDao(Database database, Runnable onChange) {
        this.database = database;
        this.tracks = new TrackDao(database);
        this.onChange = onChange;
    }

    public long insert(Track track) {
        long id = call(() -> tracks.insert(track));
        onChange.run();
        return id;
    }

    public void update(Track track) {
        run(() -> tracks.update(track));
        onChange.run();
    }

    public void updateAll(List<Track> items) {
        database.runInTransaction(() -> {
            for (Track track : items) {
                run(() -> tracks.update(track));
            }
        });
        onChange.run();
    }

    public void delete(Track track) {
        run(() -> tracks.delete(track.id));
        onChange.run();
    }

    public List<Track> getPlaylistSync(String playlist) {
        return call(() -> tracks.byPlaylist(playlist));
    }

    public @Nullable Track getByIdSync(long id) {
        return call(() -> tracks.byId(id));
    }

    public int nextPosition(String playlist) {
        return call(() -> tracks.nextPosition(playlist));
    }

    public boolean isDownloaded(String playlist, String videoId) {
        return getDownloadedByVideoIdSync(playlist, videoId) != null;
    }

    public @Nullable Track getByVideoIdSync(String playlist, String videoId) {
        return call(() -> tracks.byVideoId(playlist, videoId));
    }

    public @Nullable Track getByFilePathSync(String playlist, String filePath) {
        return call(() -> tracks.byFilePath(playlist, filePath));
    }

    public @Nullable Track getDownloadedByVideoIdSync(String playlist, String videoId) {
        return call(() -> tracks.downloadedInPlaylist(playlist, videoId));
    }

    public List<Track> getDownloadedByVideoIdGloballySync(String videoId) {
        return call(() -> tracks.downloadedByVideoIdOrdered(videoId));
    }

    public int countFileReferences(String filePath) {
        return call(() -> tracks.countByFilePath(filePath));
    }

    public void deletePending(String playlist, String videoId) {
        run(() -> tracks.deletePending(playlist, videoId));
        onChange.run();
    }

    /** Desktop: one transaction for a whole cancelled playlist instead of a commit per track. */
    public void deletePending(String playlist, List<String> videoIds) {
        database.runInTransaction(() -> {
            for (String videoId : videoIds) {
                run(() -> tracks.deletePending(playlist, videoId));
            }
        });
        onChange.run();
    }

    interface Sql<T> {
        T run() throws SQLException;
    }

    interface SqlVoid {
        void run() throws SQLException;
    }

    static <T> T call(Sql<T> sql) {
        try {
            return sql.run();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    static void run(SqlVoid sql) {
        call(() -> {
            sql.run();
            return null;
        });
    }
}
