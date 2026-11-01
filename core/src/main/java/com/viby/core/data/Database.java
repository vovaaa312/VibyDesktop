package com.viby.core.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SQLite library. Tables mirror the Android Room schema v7 so a library can move between
 * devices; {@code playlists} is a desktop addition for playlists without tracks yet.
 */
public final class Database implements AutoCloseable {

    static final int VERSION = 1;

    /** Single writer thread, like VibyDatabase.dbExecutor on Android. */
    public final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "viby-db");
        thread.setDaemon(true);
        return thread;
    });

    private final Connection connection;

    private Database(Connection connection) {
        this.connection = connection;
    }

    public static Database open(Path file) throws SQLException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (java.io.IOException e) {
            throw new SQLException("Unable to create " + file.getParent(), e);
        }
        Database database = new Database(DriverManager.getConnection("jdbc:sqlite:" + file));
        database.migrate();
        return database;
    }

    /** In-memory database for tests. */
    public static Database inMemory() throws SQLException {
        Database database = new Database(DriverManager.getConnection("jdbc:sqlite::memory:"));
        database.migrate();
        return database;
    }

    /** JDBC connections are not thread-safe; every DAO call synchronizes on it. */
    Connection connection() {
        return connection;
    }

    private void migrate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            int version;
            try (var result = statement.executeQuery("PRAGMA user_version")) {
                version = result.next() ? result.getInt(1) : 0;
            }
            if (version < 1) {
                createV1(statement);
            }
            statement.execute("PRAGMA user_version = " + VERSION);
        }
    }

    private static void createV1(Statement statement) throws SQLException {
        statement.execute("CREATE TABLE IF NOT EXISTS `tracks` ("
                + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                + "`videoId` TEXT, "
                + "`title` TEXT NOT NULL, "
                + "`uploader` TEXT, "
                + "`durationMs` INTEGER NOT NULL, "
                + "`filePath` TEXT NOT NULL, "
                + "`playlistName` TEXT NOT NULL, "
                + "`thumbnailUrl` TEXT, "
                + "`position` INTEGER NOT NULL, "
                + "`youtubePosition` INTEGER, "
                + "`youtubePlaylistItemId` TEXT, "
                + "`createdAt` INTEGER NOT NULL, "
                + "`downloaded` INTEGER NOT NULL DEFAULT 1, "
                + "`sourceUrl` TEXT, "
                + "`downloadState` TEXT NOT NULL DEFAULT 'DOWNLOADED', "
                + "`downloadErrorCode` TEXT, "
                + "`downloadErrorMessage` TEXT, "
                + "`downloadAttemptCount` INTEGER NOT NULL DEFAULT 0, "
                + "`downloadLastAttemptAt` INTEGER NOT NULL DEFAULT 0, "
                + "`downloadFormat` TEXT, "
                + "`downloadRequiresAuthorization` INTEGER NOT NULL DEFAULT 0, "
                + "`downloadProgress` INTEGER NOT NULL DEFAULT 0)");
        statement.execute("CREATE INDEX IF NOT EXISTS `index_tracks_playlistName` "
                + "ON `tracks` (`playlistName`)");
        statement.execute("CREATE INDEX IF NOT EXISTS `index_tracks_videoId` ON `tracks` (`videoId`)");
        statement.execute("CREATE TABLE IF NOT EXISTS `playlist_sources` ("
                + "`playlistName` TEXT NOT NULL, "
                + "`sourceUrl` TEXT NOT NULL, "
                + "`updatedAt` INTEGER NOT NULL, "
                + "`youtubePlaylistId` TEXT, "
                + "`youtubeSyncEnabled` INTEGER NOT NULL DEFAULT 0, "
                + "`youtubeOwnerChannelId` TEXT, "
                + "PRIMARY KEY(`playlistName`))");
        statement.execute("CREATE TABLE IF NOT EXISTS `pending_downloads` ("
                + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                + "`url` TEXT NOT NULL, "
                + "`playlistName` TEXT, "
                + "`isPlaylist` INTEGER NOT NULL, "
                + "`createdAt` INTEGER NOT NULL, "
                + "`queuePosition` INTEGER NOT NULL DEFAULT 0, "
                + "`paused` INTEGER NOT NULL DEFAULT 0, "
                + "`trackOrderJson` TEXT)");
        statement.execute("CREATE TABLE IF NOT EXISTS `playlists` ("
                + "`name` TEXT NOT NULL PRIMARY KEY, "
                + "`createdAt` INTEGER NOT NULL)");
    }

    @Override
    public void close() throws SQLException {
        executor.shutdownNow();
        synchronized (connection) {
            connection.close();
        }
    }
}
