package com.viby.core.data;

import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Playlist names (desktop table) and their YouTube sources (Android playlist_sources). */
public final class PlaylistDao {

    private final Connection connection;

    public PlaylistDao(Database database) {
        this.connection = database.connection();
    }

    /** Playlist name to creation time, oldest first. */
    public Map<String, Long> all() throws SQLException {
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT `name`, `createdAt` FROM `playlists` ORDER BY `createdAt`, `name`");
                 ResultSet result = statement.executeQuery()) {
                Map<String, Long> playlists = new LinkedHashMap<>();
                while (result.next()) {
                    playlists.put(result.getString(1), result.getLong(2));
                }
                return playlists;
            }
        }
    }

    /** Inserts the playlist; returns false when it already exists. */
    public boolean insert(String name, long createdAt) throws SQLException {
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO `playlists` (`name`, `createdAt`) VALUES (?, ?)")) {
                statement.setString(1, name);
                statement.setLong(2, createdAt);
                return statement.executeUpdate() > 0;
            }
        }
    }

    public void rename(String from, String to) throws SQLException {
        execute("UPDATE `playlists` SET `name` = ? WHERE `name` = ?", to, from);
        execute("UPDATE `playlist_sources` SET `playlistName` = ? WHERE `playlistName` = ?", to, from);
        execute("UPDATE `pending_downloads` SET `playlistName` = ? WHERE `playlistName` = ?", to, from);
    }

    public void delete(String name) throws SQLException {
        execute("DELETE FROM `playlists` WHERE `name` = ?", name);
        execute("DELETE FROM `playlist_sources` WHERE `playlistName` = ?", name);
    }

    public @Nullable PlaylistSource source(String playlistName) throws SQLException {
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM `playlist_sources` WHERE `playlistName` = ?")) {
                statement.setString(1, playlistName);
                try (ResultSet r = statement.executeQuery()) {
                    if (!r.next()) {
                        return null;
                    }
                    PlaylistSource source = new PlaylistSource();
                    source.playlistName = r.getString("playlistName");
                    source.sourceUrl = r.getString("sourceUrl");
                    source.updatedAt = r.getLong("updatedAt");
                    source.youtubePlaylistId = r.getString("youtubePlaylistId");
                    source.youtubeSyncEnabled = r.getBoolean("youtubeSyncEnabled");
                    source.youtubeOwnerChannelId = r.getString("youtubeOwnerChannelId");
                    return source;
                }
            }
        }
    }

    public void upsertSource(PlaylistSource source) throws SQLException {
        execute("INSERT OR REPLACE INTO `playlist_sources` (`playlistName`, `sourceUrl`, `updatedAt`, "
                        + "`youtubePlaylistId`, `youtubeSyncEnabled`, `youtubeOwnerChannelId`) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                source.playlistName, source.sourceUrl, source.updatedAt, source.youtubePlaylistId,
                source.youtubeSyncEnabled, source.youtubeOwnerChannelId);
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
