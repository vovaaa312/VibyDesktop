package com.viby.core.data;

import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** JDBC port of the Android TrackDao. */
public final class TrackDao {

    private static final String COLUMNS = "`videoId`, `title`, `uploader`, `durationMs`, `filePath`, "
            + "`playlistName`, `thumbnailUrl`, `position`, `youtubePosition`, `youtubePlaylistItemId`, "
            + "`createdAt`, `downloaded`, `sourceUrl`, `downloadState`, `downloadErrorCode`, "
            + "`downloadErrorMessage`, `downloadAttemptCount`, `downloadLastAttemptAt`, "
            + "`downloadFormat`, `downloadRequiresAuthorization`, `downloadProgress`";

    private final Connection connection;

    public TrackDao(Database database) {
        this.connection = database.connection();
    }

    public long insert(Track track) throws SQLException {
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO `tracks` (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                bind(statement, track);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    track.id = keys.getLong(1);
                }
                return track.id;
            }
        }
    }

    public void update(Track track) throws SQLException {
        String assignments = COLUMNS.replace("`, `", "` = ?, `") + " = ?";
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE `tracks` SET " + assignments + " WHERE `id` = ?")) {
                bind(statement, track);
                statement.setLong(22, track.id);
                statement.executeUpdate();
            }
        }
    }

    public void updateProgress(long id, String state, int progress) throws SQLException {
        execute("UPDATE `tracks` SET `downloadState` = ?, `downloadProgress` = ? WHERE `id` = ?",
                state, progress, id);
    }

    public List<Track> byPlaylist(String playlistName) throws SQLException {
        return query("SELECT * FROM `tracks` WHERE `playlistName` = ? ORDER BY `position`, `id`",
                playlistName);
    }

    public List<Track> all() throws SQLException {
        return query("SELECT * FROM `tracks` ORDER BY `playlistName`, `position`, `id`");
    }

    public @Nullable Track byId(long id) throws SQLException {
        List<Track> tracks = query("SELECT * FROM `tracks` WHERE `id` = ?", id);
        return tracks.isEmpty() ? null : tracks.get(0);
    }

    public List<Track> byIds(Collection<Long> ids) throws SQLException {
        List<Track> result = new ArrayList<>();
        for (long id : ids) {
            Track track = byId(id);
            if (track != null) {
                result.add(track);
            }
        }
        return result;
    }

    public @Nullable Track findInPlaylist(String playlistName, String videoId) throws SQLException {
        List<Track> tracks = query("SELECT * FROM `tracks` WHERE `playlistName` = ? AND `videoId` = ? "
                + "ORDER BY `downloaded` DESC LIMIT 1", playlistName, videoId);
        return tracks.isEmpty() ? null : tracks.get(0);
    }

    /** Any completely downloaded copy of a video, used to share one file across playlists. */
    public List<Track> downloadedByVideoId(String videoId) throws SQLException {
        return query("SELECT * FROM `tracks` WHERE `videoId` = ? AND `downloaded` = 1 "
                + "AND `filePath` != ''", videoId);
    }

    public int countByFilePath(String filePath) throws SQLException {
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM `tracks` WHERE `filePath` = ?")) {
                statement.setString(1, filePath);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getInt(1) : 0;
                }
            }
        }
    }

    public int nextPosition(String playlistName) throws SQLException {
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COALESCE(MAX(`position`) + 1, 0) FROM `tracks` WHERE `playlistName` = ?")) {
                statement.setString(1, playlistName);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getInt(1) : 0;
                }
            }
        }
    }

    /** Android TrackDao.getByVideoIdSync: any row of the video in the playlist. */
    public @Nullable Track byVideoId(String playlistName, String videoId) throws SQLException {
        List<Track> tracks = query("SELECT * FROM `tracks` WHERE `playlistName` = ? AND `videoId` = ? LIMIT 1",
                playlistName, videoId);
        return tracks.isEmpty() ? null : tracks.get(0);
    }

    public @Nullable Track downloadedInPlaylist(String playlistName, String videoId) throws SQLException {
        List<Track> tracks = query("SELECT * FROM `tracks` WHERE `playlistName` = ? AND `videoId` = ? "
                + "AND `downloaded` = 1 LIMIT 1", playlistName, videoId);
        return tracks.isEmpty() ? null : tracks.get(0);
    }

    public @Nullable Track byFilePath(String playlistName, String filePath) throws SQLException {
        List<Track> tracks = query("SELECT * FROM `tracks` WHERE `playlistName` = ? AND `filePath` = ? LIMIT 1",
                playlistName, filePath);
        return tracks.isEmpty() ? null : tracks.get(0);
    }

    /** Android getDownloadedByVideoIdGloballySync: every completed copy, oldest first. */
    public List<Track> downloadedByVideoIdOrdered(String videoId) throws SQLException {
        return query("SELECT * FROM `tracks` WHERE `videoId` = ? AND `downloaded` = 1 ORDER BY `id`", videoId);
    }

    /** Android deletePending: removes the not yet downloaded placeholder of a video. */
    public void deletePending(String playlistName, String videoId) throws SQLException {
        execute("DELETE FROM `tracks` WHERE `playlistName` = ? AND `videoId` = ? AND `downloaded` = 0",
                playlistName, videoId);
    }

    /** Moves every track of the playlist down by {@code count} to make room at the top. */
    public void shiftPositions(String playlistName, int count) throws SQLException {
        execute("UPDATE `tracks` SET `position` = `position` + ? WHERE `playlistName` = ?", count, playlistName);
    }

    public void delete(long id) throws SQLException {
        execute("DELETE FROM `tracks` WHERE `id` = ?", id);
    }

    public void deleteByPlaylist(String playlistName) throws SQLException {
        execute("DELETE FROM `tracks` WHERE `playlistName` = ?", playlistName);
    }

    public void renamePlaylist(String from, String to) throws SQLException {
        execute("UPDATE `tracks` SET `playlistName` = ? WHERE `playlistName` = ?", to, from);
    }

    /** Distinct playlist names that have at least one track. */
    public List<String> playlistNames() throws SQLException {
        synchronized (connection) {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT DISTINCT `playlistName` FROM `tracks`")) {
                List<String> names = new ArrayList<>();
                while (result.next()) {
                    names.add(result.getString(1));
                }
                return names;
            }
        }
    }

    /** Interrupted downloads of a previous run go back to waiting. */
    public void resetInterruptedDownloads() throws SQLException {
        execute("UPDATE `tracks` SET `downloadState` = 'WAITING' WHERE `downloaded` = 0 "
                + "AND `downloadState` IN ('DOWNLOADING', 'RETRY_SCHEDULED')");
    }

    private void execute(String sql, Object... args) throws SQLException {
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                setArgs(statement, args);
                statement.executeUpdate();
            }
        }
    }

    private List<Track> query(String sql, Object... args) throws SQLException {
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                setArgs(statement, args);
                try (ResultSet result = statement.executeQuery()) {
                    List<Track> tracks = new ArrayList<>();
                    while (result.next()) {
                        tracks.add(read(result));
                    }
                    return tracks;
                }
            }
        }
    }

    static void setArgs(PreparedStatement statement, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }

    private static void bind(PreparedStatement s, Track t) throws SQLException {
        s.setString(1, t.videoId);
        s.setString(2, t.title);
        s.setString(3, t.uploader);
        s.setLong(4, t.durationMs);
        s.setString(5, t.filePath);
        s.setString(6, t.playlistName);
        s.setString(7, t.thumbnailUrl);
        s.setInt(8, t.position);
        if (t.youtubePosition != null) {
            s.setInt(9, t.youtubePosition);
        } else {
            s.setNull(9, Types.INTEGER);
        }
        s.setString(10, t.youtubePlaylistItemId);
        s.setLong(11, t.createdAt);
        s.setBoolean(12, t.downloaded);
        s.setString(13, t.sourceUrl);
        s.setString(14, t.downloadState);
        s.setString(15, t.downloadErrorCode);
        s.setString(16, t.downloadErrorMessage);
        s.setInt(17, t.downloadAttemptCount);
        s.setLong(18, t.downloadLastAttemptAt);
        s.setString(19, t.downloadFormat);
        s.setBoolean(20, t.downloadRequiresAuthorization);
        s.setInt(21, t.downloadProgress);
    }

    private static Track read(ResultSet r) throws SQLException {
        Track t = new Track();
        t.id = r.getLong("id");
        t.videoId = r.getString("videoId");
        t.title = r.getString("title");
        t.uploader = r.getString("uploader");
        t.durationMs = r.getLong("durationMs");
        t.filePath = r.getString("filePath");
        t.playlistName = r.getString("playlistName");
        t.thumbnailUrl = r.getString("thumbnailUrl");
        t.position = r.getInt("position");
        int youtubePosition = r.getInt("youtubePosition");
        t.youtubePosition = r.wasNull() ? null : youtubePosition;
        t.youtubePlaylistItemId = r.getString("youtubePlaylistItemId");
        t.createdAt = r.getLong("createdAt");
        t.downloaded = r.getBoolean("downloaded");
        t.sourceUrl = r.getString("sourceUrl");
        t.downloadState = r.getString("downloadState");
        t.downloadErrorCode = r.getString("downloadErrorCode");
        t.downloadErrorMessage = r.getString("downloadErrorMessage");
        t.downloadAttemptCount = r.getInt("downloadAttemptCount");
        t.downloadLastAttemptAt = r.getLong("downloadLastAttemptAt");
        t.downloadFormat = r.getString("downloadFormat");
        t.downloadRequiresAuthorization = r.getBoolean("downloadRequiresAuthorization");
        t.downloadProgress = r.getInt("downloadProgress");
        return t;
    }
}
