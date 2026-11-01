package com.viby.core.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.viby.core.data.Database;
import com.viby.core.data.LibraryRepository;
import com.viby.core.data.PendingDownloadDao;
import com.viby.core.data.Track;
import com.viby.core.ytdlp.VideoInfo;
import com.viby.core.ytdlp.YtDlpException;
import com.viby.core.ytdlp.YtDlpRequest;
import com.viby.core.ytdlp.YtDlpResponse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

class DownloadManagerTest {

    @TempDir
    Path temp;

    private Database database;
    private LibraryRepository library;
    private PendingDownloadDao pending;
    private final Set<String> failingVideos = ConcurrentHashMap.newKeySet();
    private final Map<String, CountDownLatch> blockingVideos = new ConcurrentHashMap<>();
    private final Set<String> killed = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> downloadCalls = new ConcurrentHashMap<>();
    private DownloadManager manager;
    private volatile boolean playlistInfoCalled;

    @BeforeEach
    void setUp() throws Exception {
        database = Database.open(temp.resolve("viby.db"));
        library = new LibraryRepository(database);
        pending = new PendingDownloadDao(database);
        manager = newManager();
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.shutdown();
        database.close();
    }

    @Test
    void classifiesUrls() {
        assertTrue(DownloadManager.isPlaylistUrl("https://www.youtube.com/playlist?list=PL1"));
        assertTrue(DownloadManager.isPlaylistUrl("https://www.youtube.com/watch?v=abc&list=RDabc"));
        assertTrue(DownloadManager.isPlaylistUrl("https://www.youtube.com/watch?v=abc&list=PL1"));
        assertFalse(DownloadManager.isPlaylistUrl("https://youtu.be/abc"));
        assertThrows(IllegalArgumentException.class, () -> manager.enqueue("https://example.com", null));
    }

    @Test
    void parsesFlatPlaylistEntriesAndSkipsUnavailable() {
        assertNull(DownloadManager.entry(new JSONObject().put("id", "x").put("title", "[Private video]"), 0));
        DownloadManager.Entry entry = DownloadManager.entry(new JSONObject()
                .put("id", "v1").put("title", "Song").put("channel", "Band - Topic").put("duration", 61.5), 3);
        assertEquals("Band", entry.uploader());
        assertEquals(61_500, entry.durationMs());
        assertEquals(3, entry.position());
    }

    @Test
    void downloadsPlaylistIntoNamedFolderAndReusesSharedFiles() throws Exception {
        manager.start();
        manager.enqueue("https://www.youtube.com/playlist?list=PL1", null);
        await(() -> finished(1));

        List<Track> tracks = library.tracks("Road Trip");
        assertEquals(List.of("v1", "v2"), tracks.stream().map(t -> t.videoId).toList());
        assertTrue(tracks.stream().allMatch(t -> t.downloaded && new File(t.filePath).isFile()));
        assertEquals(temp.resolve("music").resolve("Road Trip").toFile(),
                new File(tracks.get(0).filePath).getParentFile());
        assertEquals("Artist", tracks.get(0).uploader);
        assertTrue(pending.all().isEmpty());

        // same video into another playlist: shares the file instead of downloading again
        manager.enqueue("https://youtu.be/v1", "Other");
        await(() -> finished(2));
        assertEquals(tracks.get(0).filePath, library.tracks("Other").get(0).filePath);
        assertEquals(1, downloadCalls.get("v1"));
    }

    @Test
    void apiListedVideosSkipYtDlpResolvingAndSurviveRestart() throws Exception {
        manager.start();
        manager.enqueueVideos("https://www.youtube.com/playlist?list=PLprivate", "Private", "My private",
                List.of(new DownloadManager.VideoEntry("p1", "Secret song", "Me", 125_000),
                        new DownloadManager.VideoEntry("p2", "Another", null, 0)));
        await(() -> finished(1));
        List<Track> tracks = library.tracks("Private");
        assertEquals(List.of("p1", "p2"), tracks.stream().map(t -> t.videoId).toList());
        assertEquals(125_000, tracks.get(0).durationMs);
        assertEquals("My private", manager.jobs().get(0).title());
        assertFalse(playlistInfoCalled);
    }

    @Test
    void permanentFailureMarksTrackAndContinues() throws Exception {
        failingVideos.add("v1");
        manager.start();
        manager.enqueue("https://www.youtube.com/playlist?list=PL1", "Mix");
        await(() -> finished(1));

        DownloadManager.JobSnapshot job = manager.jobs().get(0);
        assertEquals(DownloadManager.Status.DONE_WITH_ERRORS, job.status());
        assertEquals(1, job.failedTracks());
        Track failed = library.findInPlaylist("Mix", "v1");
        assertFalse(failed.downloaded);
        assertEquals(Track.DOWNLOAD_FAILED_PERMANENT, failed.downloadState);
        assertEquals("VIDEO_PRIVATE", failed.downloadErrorCode);
        assertTrue(library.findInPlaylist("Mix", "v2").downloaded);
    }

    @Test
    void pauseKillsProcessAndResumeFinishes() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        blockingVideos.put("v1", release);
        manager.start();
        long id = manager.enqueue("https://youtu.be/v1", "Singles");
        await(() -> manager.jobs().get(0).status() == DownloadManager.Status.DOWNLOADING
                && !blockingVideos.get("v1").equals(new Object()) && downloadCalls.containsKey("v1"));

        manager.pause(id);
        await(() -> manager.jobs().get(0).status() == DownloadManager.Status.PAUSED);
        assertFalse(killed.isEmpty());
        assertTrue(pending.all().get(0).paused);
        assertEquals(Track.DOWNLOAD_WAITING, library.findInPlaylist("Singles", "v1").downloadState);

        blockingVideos.remove("v1");
        manager.resume(id);
        await(() -> finished(1));
        assertTrue(library.findInPlaylist("Singles", "v1").downloaded);
    }

    @Test
    void cancelRemovesPlaceholdersAndRestoreResumesPendingJobs() throws Exception {
        blockingVideos.put("v1", new CountDownLatch(1));
        manager.start();
        long id = manager.enqueue("https://www.youtube.com/playlist?list=PL1", "Cancel me");
        await(() -> downloadCalls.containsKey("v1"));
        manager.cancel(id);
        await(() -> manager.jobs().isEmpty());
        assertTrue(library.tracks("Cancel me").isEmpty());
        assertTrue(pending.all().isEmpty());

        // a job persisted by a previous run is restored and completed
        blockingVideos.clear();
        manager.shutdown();
        com.viby.core.data.PendingDownload row = new com.viby.core.data.PendingDownload();
        row.url = "https://youtu.be/v2";
        row.playlistName = "Restored";
        pending.insert(row);
        manager = newManager();
        manager.start();
        await(() -> finished(1));
        assertTrue(library.findInPlaylist("Restored", "v2").downloaded);
    }

    private DownloadManager newManager() {
        YoutubeExtractor extractor = new YoutubeExtractor(request -> { }, new YoutubeExtractor.Backend() {
            @Override
            public VideoInfo getInfo(YtDlpRequest request) {
                String id = request.getUrls().get(0).replaceAll(".*/", "");
                return new VideoInfo(new JSONObject().put("id", id).put("title", "Song " + id)
                        .put("uploader", "Artist").put("duration", 200));
            }

            @Override
            public YtDlpResponse execute(YtDlpRequest request, String processId) {
                playlistInfoCalled = true;
                JSONObject root = new JSONObject().put("_type", "playlist").put("title", "Road Trip").put("entries", new JSONArray()
                        .put(new JSONObject().put("id", "v1").put("title", "First").put("channel", "Artist"))
                        .put(new JSONObject().put("id", "gone").put("title", "[Deleted video]"))
                        .put(new JSONObject().put("id", "v2").put("title", "Second").put("channel", "B")));
                return new YtDlpResponse(List.of(), 0, 0, root.toString(), "");
            }
        });
        AudioDownloader downloader = new AudioDownloader(extractor, new MediaConverter(),
                (request, processId, listener) -> {
                    String url = request.getUrls().get(0);
                    String videoId = url.substring(url.indexOf("v=") + 2);
                    downloadCalls.merge(videoId, 1, Integer::sum);
                    if (failingVideos.contains(videoId)) {
                        throw new YtDlpException("ERROR: Private video. Sign in");
                    }
                    CountDownLatch gate = blockingVideos.get(videoId);
                    if (gate != null) {
                        while (!killed.contains(processId) && blockingVideos.containsKey(videoId)) {
                            Thread.sleep(10);
                        }
                        if (killed.contains(processId)) {
                            throw new YtDlpException.Canceled(processId);
                        }
                    }
                    listener.onProgress(50f, 1, "[download]  50.0% of 1MiB");
                    String template = request.getOption("-o");
                    Files.writeString(Path.of(template.replace(".%(ext)s", ".mp3").replace("%%", "%")),
                            "audio");
                });
        return new DownloadManager(extractor, downloader, processId -> killed.add(processId), library,
                pending, () -> temp.resolve("music"), () -> "mp3");
    }

    private boolean finished(int count) {
        List<DownloadManager.JobSnapshot> jobs = manager.jobs();
        return jobs.size() == count && jobs.stream().allMatch(DownloadManager.JobSnapshot::isFinished);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(20);
        }
    }
}
