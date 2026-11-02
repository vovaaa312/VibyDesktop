package com.viby.core.sync;

import com.viby.core.data.Database;
import com.viby.core.data.PlaylistDao;
import com.viby.core.data.PlaylistSource;
import com.viby.core.data.Track;
import com.viby.core.data.TrackDao;

import org.jspecify.annotations.Nullable;

import java.sql.SQLException;
import java.util.List;

/**
 * Room-shaped, unchecked view of the desktop DAOs so the Android YoutubePlaylistSync logic is
 * ported with its method calls unchanged.
 */
final class SyncStore {

    private final Database database;
    private final TrackDao tracks;
    private final PlaylistDao playlists;

    SyncStore(Database database) {
        this.database = database;
        this.tracks = new TrackDao(database);
        this.playlists = new PlaylistDao(database);
    }

    void runInTransaction(Runnable body) {
        database.runInTransaction(body);
    }

    List<Track> getPlaylistSync(String playlist) {
        return call(() -> tracks.byPlaylist(playlist));
    }

    void updateAll(List<Track> items) {
        call(() -> {
            for (Track track : items) {
                tracks.update(track);
            }
            return null;
        });
    }

    @Nullable Track getDownloadedByVideoIdSync(String playlist, String videoId) {
        Track track = call(() -> tracks.findInPlaylist(playlist, videoId));
        return track != null && track.downloaded ? track : null;
    }

    @Nullable Track getByVideoIdSync(String playlist, String videoId) {
        return call(() -> tracks.findInPlaylist(playlist, videoId));
    }

    int nextPosition(String playlist) {
        return call(() -> tracks.nextPosition(playlist));
    }

    long insert(Track track) {
        return call(() -> tracks.insert(track));
    }

    void delete(Track track) {
        call(() -> {
            tracks.delete(track.id);
            return null;
        });
    }

    int countFileReferences(String filePath) {
        return call(() -> tracks.countByFilePath(filePath));
    }

    @Nullable PlaylistSource getSync(String playlist) {
        return call(() -> playlists.source(playlist));
    }

    void upsert(PlaylistSource source) {
        call(() -> {
            playlists.upsertSource(source);
            return null;
        });
    }

    private interface Sql<T> {
        T run() throws SQLException;
    }

    private static <T> T call(Sql<T> sql) {
        try {
            return sql.run();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
