package com.viby.core.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.viby.core.data.Database;
import com.viby.core.data.LibraryRepository;
import com.viby.core.data.PlaylistSource;
import com.viby.core.data.Track;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class PlaylistWatcherTest {

    private static final PlaylistWatcher.Policy OFTEN = new PlaylistWatcher.Policy(false, 0, 86_400_000L);
    private static final PlaylistWatcher.Policy HOURLY = new PlaylistWatcher.Policy(false, 3_600_000L, 86_400_000L);

    @TempDir
    Path temp;

    private Database database;
    private LibraryRepository library;
    /** What YouTube returns per playlist id. */
    private final Map<String, List<String>> remote = new HashMap<>();
    /** Playlists yt-dlp cannot open (private). */
    private final Set<String> privateIds = new HashSet<>();
    private boolean signedIn;
    private final List<String> queued = new ArrayList<>();
    /** "ytdlp:ID", "api:ID", "counts", "durations:a,b" in call order. */
    private final List<String> calls = new ArrayList<>();
    private List<DownloadService.VideoEntry> lastQueued;

    @BeforeEach
    void open() throws Exception {
        database = Database.open(temp.resolve("viby.db"));
        library = new LibraryRepository(database);
    }

    @AfterEach
    void close() throws Exception {
        database.close();
    }

    private PlaylistWatcher watcher() {
        PlaylistWatcher.Queue queue = new PlaylistWatcher.Queue() {
            @Override
            public boolean busy(String url, String playlist) {
                return false;
            }

            @Override
            public void enqueue(String url, String playlist, List<DownloadService.VideoEntry> videos, List<String> only) {
                lastQueued = videos;
                queued.add(playlist + ":" + String.join(",", only));
            }
        };
        return new PlaylistWatcher(temp.resolve("watched.json"), library, queue, new PlaylistWatcher.Lister() {
            @Override
            public PlaylistWatcher.Remote ytDlp(String id) throws Exception {
                calls.add("ytdlp:" + id);
                if (privateIds.contains(id)) {
                    throw new IOException("This playlist is private");
                }
                return listing(id);
            }

            @Override
            public boolean signedIn() {
                return signedIn;
            }

            @Override
            public PlaylistWatcher.Remote api(String id) {
                calls.add("api:" + id);
                return listing(id);
            }

            @Override
            public Map<String, Integer> counts(List<String> ids) {
                calls.add("counts");
                Map<String, Integer> counts = new HashMap<>();
                for (String id : ids) {
                    counts.put(id, remote.get(id).size());
                }
                return counts;
            }

            @Override
            public Map<String, Long> durations(List<String> videoIds) {
                calls.add("durations:" + String.join(",", videoIds));
                Map<String, Long> result = new HashMap<>();
                videoIds.forEach(id -> result.put(id, 123_000L));
                return result;
            }
        });
    }

    private PlaylistWatcher.Remote listing(String id) {
        List<DownloadService.VideoEntry> videos = new ArrayList<>();
        for (String videoId : remote.get(id)) {
            videos.add(new DownloadService.VideoEntry(videoId, "Title " + videoId, null, 0));
        }
        return new PlaylistWatcher.Remote(id, videos);
    }

    private void playlist(String name, String url, String... videoIds) {
        library.ensurePlaylist(name);
        PlaylistSource source = new PlaylistSource();
        source.playlistName = name;
        source.sourceUrl = url;
        library.saveSource(source);
        for (String videoId : videoIds) {
            Track track = new Track();
            track.playlistName = name;
            track.videoId = videoId;
            track.title = "Title " + videoId;
            track.filePath = "";
            library.insert(track);
        }
    }

    @Test
    void firstCheckRemembersThenOnlyNewVideosAreQueued() {
        playlist("Drive", "https://www.youtube.com/playlist?list=PLdrive", "a", "b");
        remote.put("PLdrive", List.of("a", "b", "c"));

        PlaylistWatcher.Result first = watcher().check(Set.of(), OFTEN);
        assertEquals(1, first.checked());
        assertTrue(queued.isEmpty(), "the first check only remembers what is there: " + queued);

        remote.put("PLdrive", List.of("a", "b", "c", "d", "e"));
        // a fresh instance reads the remembered state from the file
        PlaylistWatcher.Result second = watcher().check(Set.of(), OFTEN);
        assertEquals(List.of("Drive:d,e"), queued);
        assertEquals(2, second.newTracks());
        assertEquals(5, lastQueued.size(), "the whole list sets the YouTube order");
        assertTrue(watcher().lastCheck() > 0);
    }

    @Test
    void aTrackDeletedFromVibyDoesNotComeBack() {
        playlist("Drive", "https://www.youtube.com/playlist?list=PLdrive", "a", "b");
        remote.put("PLdrive", List.of("a", "b"));
        watcher().check(Set.of(), OFTEN);

        Track b = library.tracks("Drive").stream().filter(t -> "b".equals(t.videoId)).findFirst().orElseThrow();
        library.removeTracks(List.of(b.id));
        PlaylistWatcher.Result result = watcher().check(Set.of(), PlaylistWatcher.Policy.MANUAL);
        assertTrue(queued.isEmpty(), queued.toString());
        assertEquals(0, result.newTracks());
    }

    @Test
    void radiosLocalPlaylistsAndExcludedOnesAreSkipped() {
        playlist("Radio", "https://www.youtube.com/watch?v=x&list=RDx", "x");
        playlist("Local", "", "y");
        playlist("Drive", "https://www.youtube.com/playlist?list=PLdrive", "a");
        remote.put("PLdrive", List.of("a"));

        assertEquals(0, watcher().check(Set.of("Drive"), OFTEN).checked());
        assertTrue(calls.isEmpty(), calls.toString());
        assertEquals(1, watcher().check(Set.of(), OFTEN).checked());
        assertEquals(List.of("ytdlp:PLdrive"), calls);
    }

    @Test
    void withAnAccountOnlyPlaylistsThatChangedSizeAreRead() {
        signedIn = true;
        playlist("Drive", "https://www.youtube.com/playlist?list=PLdrive", "a");
        playlist("Chill", "https://www.youtube.com/playlist?list=PLchill", "x");
        remote.put("PLdrive", List.of("a"));
        remote.put("PLchill", List.of("x"));
        watcher().check(Set.of(), OFTEN);
        calls.clear();

        // nothing changed: one cheap request, no playlist is read
        watcher().check(Set.of(), OFTEN);
        assertEquals(List.of("counts"), calls);

        calls.clear();
        remote.put("PLchill", List.of("x", "y"));
        watcher().check(Set.of(), OFTEN);
        // public playlist: read with yt-dlp (no quota), not with the API
        assertEquals(List.of("counts", "ytdlp:PLchill"), calls);
        assertEquals(List.of("Chill:y"), queued);
    }

    @Test
    void privatePlaylistsAreReadThroughTheAccountWithDurationsOfNewVideosOnly() {
        signedIn = true;
        privateIds.add("PLsecret");
        playlist("Secret", "https://www.youtube.com/playlist?list=PLsecret", "a");
        remote.put("PLsecret", List.of("a"));
        watcher().check(Set.of(), OFTEN);
        assertEquals(List.of("counts", "ytdlp:PLsecret", "api:PLsecret"), calls);

        calls.clear();
        remote.put("PLsecret", List.of("a", "b"));
        watcher().check(Set.of(), OFTEN);
        // remembered as private: yt-dlp is not tried again
        assertEquals(List.of("counts", "api:PLsecret", "durations:b"), calls);
        assertEquals(List.of("Secret:b"), queued);
        assertEquals(123_000L, lastQueued.get(1).durationMs());
        assertEquals(0L, lastQueued.get(0).durationMs());
    }

    @Test
    void withoutAnAccountYtDlpReadsAPlaylistAtMostByTheInterval() {
        playlist("Drive", "https://www.youtube.com/playlist?list=PLdrive", "a");
        remote.put("PLdrive", List.of("a"));
        watcher().check(Set.of(), HOURLY);
        calls.clear();
        watcher().check(Set.of(), HOURLY);
        assertTrue(calls.isEmpty(), "read less than an hour ago: " + calls);
        watcher().check(Set.of(), PlaylistWatcher.Policy.MANUAL);
        assertEquals(List.of("ytdlp:PLdrive"), calls);
    }

    @Test
    void parsesTheYtDlpListing() {
        PlaylistWatcher.Remote parsed = PlaylistWatcher.parse("""
                {"title":"Drive","entries":[
                  {"id":"a","title":"Song A","channel":"Band","duration":201.5},
                  {"id":"","title":"broken"},
                  {"id":"b","title":"Song B","uploader":"Other"}]}""");
        assertEquals("Drive", parsed.title());
        assertEquals(2, parsed.videos().size());
        assertEquals(new DownloadService.VideoEntry("a", "Song A", "Band", 201_500), parsed.videos().get(0));
        assertEquals("Other", parsed.videos().get(1).uploader());
    }
}
