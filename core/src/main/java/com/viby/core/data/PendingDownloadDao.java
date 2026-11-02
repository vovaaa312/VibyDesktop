package com.viby.core.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Persists unfinished download jobs so the queue survives restarts. */
public final class PendingDownloadDao {

    private final Connection connection;

    public PendingDownloadDao(Database database) {
        this.connection = database.connection();
    }

    public long insert(PendingDownload pending) throws SQLException {
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO `pending_downloads` (`url`, `playlistName`, `isPlaylist`, `createdAt`, "
                            + "`queuePosition`, `paused`, `trackOrderJson`, `entriesJson`) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                TrackDao.setArgs(statement, pending.url, pending.playlistName, pending.isPlaylist,
                        pending.createdAt, pending.queuePosition, pending.paused, pending.trackOrderJson,
                        pending.entriesJson);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    pending.id = keys.getLong(1);
                }
                return pending.id;
            }
        }
    }

    public List<PendingDownload> all() throws SQLException {
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM `pending_downloads` ORDER BY `queuePosition`, `id`");
                 ResultSet r = statement.executeQuery()) {
                List<PendingDownload> result = new ArrayList<>();
                while (r.next()) {
                    PendingDownload pending = new PendingDownload();
                    pending.id = r.getLong("id");
                    pending.url = r.getString("url");
                    pending.playlistName = r.getString("playlistName");
                    pending.isPlaylist = r.getBoolean("isPlaylist");
                    pending.createdAt = r.getLong("createdAt");
                    pending.queuePosition = r.getLong("queuePosition");
                    pending.paused = r.getBoolean("paused");
                    pending.trackOrderJson = r.getString("trackOrderJson");
                    pending.entriesJson = r.getString("entriesJson");
                    result.add(pending);
                }
                return result;
            }
        }
    }

    public void setPaused(long id, boolean paused) throws SQLException {
        execute("UPDATE `pending_downloads` SET `paused` = ? WHERE `id` = ?", paused, id);
    }

    public void setQueuePosition(long id, long position) throws SQLException {
        execute("UPDATE `pending_downloads` SET `queuePosition` = ? WHERE `id` = ?", position, id);
    }

    public void setTrackOrder(long id, String trackOrderJson) throws SQLException {
        execute("UPDATE `pending_downloads` SET `trackOrderJson` = ? WHERE `id` = ?", trackOrderJson, id);
    }

    public void setPlaylistName(long id, String playlistName) throws SQLException {
        execute("UPDATE `pending_downloads` SET `playlistName` = ? WHERE `id` = ?", playlistName, id);
    }

    public void delete(long id) throws SQLException {
        execute("DELETE FROM `pending_downloads` WHERE `id` = ?", id);
    }

    private void execute(String sql, Object... args) throws SQLException {
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                TrackDao.setArgs(statement, args);
                statement.executeUpdate();
            }
        }
    }
}
