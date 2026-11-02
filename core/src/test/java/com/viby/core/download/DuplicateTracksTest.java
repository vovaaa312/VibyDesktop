package com.viby.core.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.viby.core.data.Database;
import com.viby.core.data.RoomTrackDao;
import com.viby.core.data.Track;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class DuplicateTracksTest {

    private static final String URL = "https://www.youtube.com/watch?v=abcdefghijk";

    @TempDir
    Path temp;

    private Database database;
    private RoomTrackDao dao;
    private DuplicateTracks duplicates;

    @BeforeEach
    void open() throws Exception {
        database = Database.open(temp.resolve("viby.db"));
        dao = new RoomTrackDao(database);
        duplicates = new DuplicateTracks(dao);
    }

    @AfterEach
    void close() throws Exception {
        database.close();
    }

    @Test
    void asksOnlyAboutDownloadedSingleVideosOfTheTargetPlaylist() {
        insert(DownloadService.DEFAULT_PLAYLIST, true);

        assertNotNull(duplicates.existing(URL, null, false));
        assertNotNull(duplicates.existing(URL, "  ", false));
        assertNull(duplicates.existing(URL, "Other", false));
        assertNull(duplicates.existing(URL + "&list=PL123", null, true));
        assertNull(duplicates.existing("https://example.com/not-youtube", null, false));
    }

    @Test
    void pendingPlaceholderIsNotADuplicate() {
        insert("Mix", false);
        assertNull(duplicates.existing(URL, "Mix", false));
    }

    @Test
    void duplicateSharesTheFileAndGoesToTheEnd() {
        Track source = insert("Mix", true);

        assertTrue(duplicates.addDuplicate("Mix", "abcdefghijk", URL));

        List<Track> tracks = dao.getPlaylistSync("Mix");
        assertEquals(2, tracks.size());
        Track copy = tracks.stream().filter(t -> t.id != source.id).findFirst().orElseThrow();
        assertEquals(source.filePath, copy.filePath);
        assertEquals("Song", copy.title);
        assertEquals(source.position + 1, copy.position);
        assertNull(copy.youtubePosition);
        assertEquals("https://youtu.be/abcdefghijk", copy.sourceUrl);
    }

    @Test
    void vanishedSourceFallsBackToANormalDownload() {
        assertFalse(duplicates.addDuplicate("Mix", "abcdefghijk", URL));
        assertTrue(dao.getPlaylistSync("Mix").isEmpty());
    }

    private Track insert(String playlist, boolean downloaded) {
        Track track = new Track();
        track.videoId = "abcdefghijk";
        track.title = "Song";
        track.playlistName = playlist;
        track.position = 3;
        track.youtubePosition = 0;
        track.sourceUrl = "https://youtu.be/abcdefghijk";
        track.downloaded = downloaded;
        track.downloadState = downloaded ? Track.DOWNLOAD_DOWNLOADED : Track.DOWNLOAD_WAITING;
        track.filePath = downloaded ? temp.resolve("song.mp3").toString() : "";
        track.id = dao.insert(track);
        return track;
    }
}
