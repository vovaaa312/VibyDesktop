package com.viby.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class LibraryRepositoryTest {

    @TempDir
    Path temp;

    private final List<Database> opened = new ArrayList<>();
    private LibraryRepository library;
    private final AtomicInteger changes = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        library = new LibraryRepository(open());
        library.addListener(changes::incrementAndGet);
    }

    @Test
    void createsRenamesAndRejectsDuplicatePlaylists() {
        assertTrue(library.createPlaylist("Chill"));
        assertFalse(library.createPlaylist("Chill"));
        library.insert(track("Chill", "a", null));
        assertTrue(library.renamePlaylist("Chill", "Evening"));
        assertEquals(List.of("Evening"), names());
        assertEquals("Evening", library.tracks("Evening").get(0).playlistName);
        assertTrue(changes.get() >= 3);
    }

    @Test
    void summariesIncludeEmptyPlaylistsAndTotals() {
        library.createPlaylist("Empty");
        library.insert(track("Drive", "a", null));
        library.insert(track("Drive", "b", null));
        LibraryRepository.PlaylistSummary drive = library.playlists().stream()
                .filter(p -> p.name().equals("Drive")).findFirst().orElseThrow();
        assertEquals(2, drive.trackCount());
        assertEquals(360_000, drive.durationMs());
        assertEquals("thumb-a", drive.coverUrl());
        assertTrue(names().contains("Empty"));
    }

    @Test
    void sharedFileIsDeletedOnlyWithItsLastTrack() throws Exception {
        Path file = Files.writeString(temp.resolve("song.mp3"), "audio");
        Track first = library.insert(track("One", "v1", file.toString()));
        Track second = library.insert(track("Two", "v1", file.toString()));
        assertEquals(first.filePath, library.findReusableDownload("v1").filePath);

        library.removeTracks(List.of(first.id));
        assertTrue(Files.exists(file));
        library.deletePlaylist("Two");
        assertFalse(Files.exists(file));
        assertNull(library.findReusableDownload("v1"));
        assertNull(library.track(second.id));
    }

    @Test
    void moveTracksAppendsToTarget() {
        Track moving = library.insert(track("A", "x", null));
        library.insert(track("B", "y", null));
        library.moveTracks(List.of(moving.id), "B");
        List<Track> target = library.tracks("B");
        assertEquals(List.of("y", "x"), target.stream().map(t -> t.videoId).toList());
        assertTrue(library.tracks("A").isEmpty());
    }

    @Test
    void copyTracksSharesFilesAndSkipsVideosAlreadyInTarget() throws Exception {
        Path file = Files.writeString(temp.resolve("song.mp3"), "audio");
        Track source = library.insert(track("Source", "s1", file.toString()));
        library.insert(track("Target", "t1", null));
        assertEquals(1, library.copyTracks(List.of(source.id), "Target"));
        assertEquals(0, library.copyTracks(List.of(source.id), "Target"));
        assertEquals(List.of("s1", "t1"), library.tracks("Target").stream().map(t -> t.videoId).toList());
        assertEquals(1, library.tracks("Source").size());
        Track copy = library.findInPlaylist("Target", "s1");
        assertEquals(file.toString(), copy.filePath);
        assertTrue(copy.downloaded);
        assertNull(copy.youtubePlaylistItemId);
    }

    @Test
    void persistsAcrossReopen() throws Exception {
        library.insert(track("Keep", "k", null));
        LibraryRepository reopened = new LibraryRepository(open());
        assertEquals("k", reopened.findInPlaylist("Keep", "k").videoId);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Database database : opened) {
            database.close();
        }
    }

    private Database open() throws Exception {
        Database database = Database.open(temp.resolve("viby.db"));
        opened.add(database);
        return database;
    }

    private List<String> names() {
        return library.playlists().stream().map(LibraryRepository.PlaylistSummary::name).toList();
    }

    private static Track track(String playlist, String videoId, String filePath) {
        Track track = new Track();
        track.playlistName = playlist;
        track.videoId = videoId;
        track.title = "Title " + videoId;
        track.durationMs = 180_000;
        track.thumbnailUrl = "thumb-" + videoId;
        track.filePath = filePath != null ? filePath : "";
        track.downloaded = filePath != null;
        return track;
    }
}
