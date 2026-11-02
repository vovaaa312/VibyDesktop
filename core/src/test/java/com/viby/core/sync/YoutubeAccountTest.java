package com.viby.core.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.viby.core.data.Database;
import com.viby.core.data.LibraryRepository;
import com.viby.core.data.Track;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class YoutubeAccountTest {

    @TempDir
    Path temp;

    private Database database;

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void onlyTracksNeverSeenOnYoutubeArePushedAutomatically() throws Exception {
        database = Database.open(temp.resolve("viby.db"));
        LibraryRepository library = new LibraryRepository(database);
        YoutubeAccount account = new YoutubeAccount(new GoogleOAuth(null, temp.resolve("token.json")),
                database, library, null);

        library.insert(track("linked", 0, "item-1"));       // on YouTube now
        library.insert(track("removedRemotely", 4, null));  // came from YouTube, deleted there since
        library.insert(track("sentHere", null, null));      // "send to playlist" copy
        library.insert(track(null, null, null));            // local file without a YouTube id

        assertEquals(List.of("sentHere"), account.localAdditions("favs"));
    }

    private static Track track(String videoId, Integer youtubePosition, String itemId) {
        Track track = new Track();
        track.playlistName = "favs";
        track.videoId = videoId;
        track.title = String.valueOf(videoId);
        track.youtubePosition = youtubePosition;
        track.youtubePlaylistItemId = itemId;
        return track;
    }
}
