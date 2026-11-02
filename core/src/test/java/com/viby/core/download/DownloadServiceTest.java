package com.viby.core.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.viby.core.data.Database;
import com.viby.core.data.LibraryRepository;
import com.viby.core.data.PendingDownload;
import com.viby.core.data.PendingDownloadDao;
import com.viby.core.data.RoomPendingDownloadDao;
import com.viby.core.data.RoomTrackDao;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/** Desktop DownloadService (port of the Android service) against a real SQLite library. */
class DownloadServiceTest {

    private static final String PLAYLIST_URL = "https://www.youtube.com/playlist?list=PL1";
    private static final String SECOND_PLAYLIST_URL = "https://www.youtube.com/playlist?list=PL2";

    @TempDir
    Path temp;

    private Database database;
    private LibraryRepository library;
    private PendingDownloadDao pending;
    private final Set<String> failingVideos = ConcurrentHashMap.newKeySet();
    private final Set<String> blockingVideos = ConcurrentHashMap.newKeySet();
    private final Set<String> killed = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> downloadCalls = new ConcurrentHashMap<>();
    private final List<String> downloadOrder = new CopyOnWriteArrayList<>();
    private volatile List<String> playlistEntries = List.of("v1", "v2");
    private volatile boolean playlistInfoCalled;
    private final java.util.concurrent.atomic.AtomicInteger infoCalls = new java.util.concurrent.atomic.AtomicInteger();
    private DownloadService service;

    @BeforeEach
    void setUp() throws Exception {
        database = Database.open(temp.resolve("viby.db"));
        library = new LibraryRepository(database);
        pending = new PendingDownloadDao(database);
        service = newService();
    }

    @AfterEach
    void tearDown() throws Exception {
        blockingVideos.clear();
        service.shutdown();
        database.close();
    }

    @Test
    void classifiesUrlsLikeTheAndroidDownloadScreen() {
        assertTrue(DownloadService.isPlaylistUrl(PLAYLIST_URL));
        assertTrue(DownloadService.isPlaylistUrl("https://www.youtube.com/watch?v=abc&list=RDabc"));
        assertTrue(DownloadService.isPlaylistUrl("https://www.youtube.com/watch?v=abc&list=PL1"));
        assertFalse(DownloadService.isPlaylistUrl("https://youtu.be/abc"));
    }

    @Test
    void downloadsPlaylistInSourceOrderAndSharesFilesAcrossPlaylists() throws Exception {
        service.enqueue(PLAYLIST_URL, null, true);
        await(() -> finished(1));

        List<Track> tracks = library.tracks("Road Trip");
        assertEquals(List.of("v1", "v2"), tracks.stream().map(t -> t.videoId).toList());
        assertTrue(tracks.stream().allMatch(t -> t.downloaded && new File(t.filePath).isFile()));
        assertEquals(temp.resolve("music").resolve("Road Trip").toFile(),
                new File(tracks.get(0).filePath).getParentFile());
        assertEquals(PLAYLIST_URL, library.source("Road Trip").sourceUrl);
        await(() -> pendingRows() == 0);

        // the same video in another playlist reuses the file instead of downloading it again
        service.enqueue("https://youtu.be/v1", "Other", false);
        await(() -> finished(2));
        assertEquals(tracks.get(0).filePath, library.tracks("Other").get(0).filePath);
        assertEquals(1, downloadCalls.get("v1"));
    }

    @Test
    void permanentFailureMarksTheTrackAndTheJobContinues() throws Exception {
        failingVideos.add("v1");
        service.enqueue(PLAYLIST_URL, "Mix", true);
        await(() -> finished(1));

        DownloadJob job = service.getJobs().get(0);
        assertEquals(DownloadJob.Status.COMPLETED_WITH_ERRORS, job.status);
        assertEquals(1, job.failedCount);
        Track failed = library.findInPlaylist("Mix", "v1");
        assertFalse(failed.downloaded);
        assertEquals(Track.DOWNLOAD_FAILED_PERMANENT, failed.downloadState);
        assertEquals("VIDEO_PRIVATE", failed.downloadErrorCode);
        assertTrue(library.findInPlaylist("Mix", "v2").downloaded);

        // retry from the job's track list, then drop the failed placeholder
        failingVideos.clear();
        service.retryTrack(job.id, "v1");
        await(() -> job.status == DownloadJob.Status.DONE);
        assertTrue(library.findInPlaylist("Mix", "v1").downloaded);
    }

    @Test
    void removeFailedTrackDeletesItsPlaceholder() throws Exception {
        failingVideos.add("v1");
        service.enqueue(PLAYLIST_URL, "Mix", true);
        await(() -> finished(1));
        DownloadJob job = service.getJobs().get(0);
        service.removeFailedTrack(job.id, "v1");
        await(() -> library.findInPlaylist("Mix", "v1") == null);
        assertEquals(DownloadJob.Status.DONE, job.status);
    }

    @Test
    void pauseKillsTheProcessAndResumeFinishes() throws Exception {
        blockingVideos.add("v1");
        service.enqueue("https://youtu.be/v1", "Singles", false);
        await(() -> downloadCalls.containsKey("v1"));
        long id = service.getJobs().get(0).id;

        service.pause(id);
        await(() -> service.getJobs().get(0).status == DownloadJob.Status.PAUSED);
        assertFalse(killed.isEmpty());
        await(() -> pausedRows() == 1);

        blockingVideos.clear();
        service.resume(id);
        await(() -> finished(1));
        assertTrue(library.findInPlaylist("Singles", "v1").downloaded);
    }

    @Test
    void cancelRemovesPlaceholdersAndRestoreResumesPersistedJobs() throws Exception {
        blockingVideos.add("v1");
        service.enqueue(PLAYLIST_URL, "Cancel me", true);
        await(() -> downloadCalls.containsKey("v1"));
        service.cancel(service.getJobs().get(0).id);
        await(() -> finished(1) && library.tracks("Cancel me").isEmpty());
        await(() -> pendingRows() == 0);

        // a job saved by a previous run is restored and completed
        blockingVideos.clear();
        service.shutdown();
        PendingDownload row = new PendingDownload();
        row.url = "https://youtu.be/v2";
        row.playlistName = "Restored";
        pending.insert(row);
        service = newService();
        service.restorePending();
        await(() -> finished(1));
        assertTrue(library.findInPlaylist("Restored", "v2").downloaded);
    }

    @Test
    void sameUrlAndPlaylistIsNotQueuedTwiceWhileActive() throws Exception {
        blockingVideos.add("v1");
        service.enqueue(PLAYLIST_URL, "Mix", true);
        service.enqueue(PLAYLIST_URL, "Mix", true);
        assertTrue(service.hasActiveJob(PLAYLIST_URL, "Mix"));
        assertEquals(1, service.getJobs().size());
        blockingVideos.clear();
        await(() -> finished(1));
        assertFalse(service.hasActiveJob(PLAYLIST_URL, "Mix"));
    }

    @Test
    void refreshPutsNewTracksAtTheirSourcePositions() throws Exception {
        Track second = new Track();
        second.playlistName = "Road Trip";
        second.videoId = "v2";
        second.title = "Second";
        second.youtubePosition = 0;
        second.filePath = Files.writeString(temp.resolve("second.mp3"), "audio").toString();
        library.insert(second);
        Track local = new Track();
        local.playlistName = "Road Trip";
        local.title = "Local file";
        local.filePath = Files.writeString(temp.resolve("local.mp3"), "audio").toString();
        library.insert(local);

        service.enqueue(PLAYLIST_URL, "Road Trip", true);
        await(() -> finished(1));

        assertEquals(Arrays.asList("v1", "v2", null),
                library.tracks("Road Trip").stream().map(t -> t.videoId).toList());
    }

    @Test
    void prioritizedTrackJumpsTheQueueAndInterruptsTheRunningDownload() throws Exception {
        playlistEntries = List.of("v1", "v2", "v3");
        blockingVideos.add("v1");
        service.enqueue(PLAYLIST_URL, "Mix", true);
        await(() -> downloadCalls.containsKey("v1"));

        // the user taps the last pending track: it is downloaded next, v1 yields
        service.prioritizeTrack("Mix", "v3");
        await(() -> downloadOrder.contains("v3"));
        blockingVideos.clear();
        await(() -> finished(1));
        assertEquals("v3", downloadOrder.get(1));
        assertFalse(killed.isEmpty());
        assertTrue(library.tracks("Mix").stream().allMatch(t -> t.downloaded));
    }

    @Test
    void waitingTracksCanBeReorderedInsideAJob() throws Exception {
        playlistEntries = List.of("v1", "v2", "v3");
        blockingVideos.add("v1");
        service.enqueue(PLAYLIST_URL, "Mix", true);
        await(() -> downloadCalls.containsKey("v1"));
        DownloadJob job = service.getJobs().get(0);

        service.moveTrack(job.id, "v3", "v2");
        assertEquals(List.of("v1", "v3", "v2"), job.tracks.stream().map(t -> t.videoId).toList());
        blockingVideos.clear();
        await(() -> finished(1));
        assertEquals(List.of("v1", "v3", "v2"), downloadOrder);
    }

    @Test
    void jobDraggedAboveTheRunningOneTakesOver() throws Exception {
        blockingVideos.add("v1");
        service.enqueue(PLAYLIST_URL, "Mix", true);
        await(() -> downloadCalls.containsKey("v1"));
        service.enqueue(SECOND_PLAYLIST_URL, "Other", true);
        await(() -> service.getJobs().size() == 2);
        DownloadJob running = service.getJobs().get(0);
        DownloadJob queued = service.getJobs().get(1);

        // desktop: the topmost job always downloads
        service.moveJob(queued.id, running.id);
        await(() -> downloadOrder.contains("w2"));
        blockingVideos.clear();
        await(() -> finished(2));
        assertEquals(List.of("v1", "w1", "w2", "v1", "v2"), downloadOrder);
        // the yielded job restarted from the playlist info it had already fetched
        assertEquals(2, infoCalls.get());
    }

    @Test
    void waitingTrackDroppedAboveTheDownloadingOneStartsAtOnce() throws Exception {
        playlistEntries = List.of("v1", "v2", "v3");
        blockingVideos.add("v1");
        service.enqueue(PLAYLIST_URL, "Mix", true);
        await(() -> downloadCalls.containsKey("v1"));
        DownloadJob job = service.getJobs().get(0);

        service.moveTrack(job.id, "v3", "v1");
        await(() -> downloadOrder.contains("v3"));
        blockingVideos.clear();
        await(() -> finished(1));
        assertEquals(List.of("v1", "v3", "v1", "v2"), downloadOrder);
        assertEquals(1, infoCalls.get());
    }

    @Test
    void downloadingTrackDraggedDownWaitsForTheTarget() throws Exception {
        playlistEntries = List.of("v1", "v2", "v3");
        blockingVideos.add("v1");
        service.enqueue(PLAYLIST_URL, "Mix", true);
        await(() -> downloadCalls.containsKey("v1"));
        DownloadJob job = service.getJobs().get(0);

        service.moveTrack(job.id, "v1", "v2");
        await(() -> downloadOrder.contains("v2"));
        blockingVideos.clear();
        await(() -> finished(1));
        assertEquals(List.of("v1", "v2", "v1", "v3"), downloadOrder);
        assertEquals(List.of("v2", "v1", "v3"), job.tracks.stream().map(t -> t.videoId).toList());
        assertTrue(library.tracks("Mix").stream().allMatch(t -> t.downloaded));
    }

    @Test
    void apiListedVideosSkipYtDlpResolving() throws Exception {
        service.enqueueVideos("https://www.youtube.com/playlist?list=PLprivate", "Private", "My private",
                List.of(new DownloadService.VideoEntry("p1", "Secret song", "Me", 125_000),
                        new DownloadService.VideoEntry("p2", "Another", null, 0)));
        await(() -> finished(1));
        List<Track> tracks = library.tracks("Private");
        assertEquals(List.of("p1", "p2"), tracks.stream().map(t -> t.videoId).toList());
        assertEquals(125_000, tracks.get(0).durationMs);
        assertEquals("My private", service.getJobs().get(0).title);
        assertFalse(playlistInfoCalled);
    }

    @Test
    void parsesTotalSizeFromYtDlpProgress() {
        DownloadJob job = new DownloadJob("url", null, false);
        job.progress = 50;
        DownloadService.parseSizes(job, "[download]  50.0% of ~ 4.00MiB at 1.00MiB/s");
        assertEquals(4L * 1024 * 1024, job.totalBytes);
        assertEquals(2L * 1024 * 1024, job.downloadedBytes);
        assertNotEquals(0, job.totalBytes);
    }

    private DownloadService newService() {
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
                infoCalls.incrementAndGet();
                List<String> ids = request.getUrls().get(0).contains("PL2") ? List.of("w1", "w2") : playlistEntries;
                JSONArray entries = new JSONArray();
                for (String id : ids) {
                    entries.put(new JSONObject().put("id", id).put("title", "Title " + id).put("channel", "Artist"));
                }
                JSONObject root = new JSONObject().put("title", "Road Trip").put("entries", entries);
                return new YtDlpResponse(List.of(), 0, 0, root.toString(), "");
            }
        });
        AudioDownloader downloader = new AudioDownloader(extractor, new MediaConverter(),
                (request, processId, listener) -> {
                    String url = request.getUrls().get(0);
                    String videoId = url.contains("v=") ? url.substring(url.indexOf("v=") + 2) : url.replaceAll(".*/", "");
                    downloadCalls.merge(videoId, 1, Integer::sum);
                    downloadOrder.add(videoId);
                    if (failingVideos.contains(videoId)) {
                        throw new YtDlpException("ERROR: Private video. Sign in");
                    }
                    while (blockingVideos.contains(videoId) && !killed.contains(processId)) {
                        Thread.sleep(10);
                    }
                    if (killed.remove(processId)) {
                        killed.add("killed:" + processId);
                        throw new YtDlpException.Canceled(processId);
                    }
                    listener.onProgress(50f, 1, "[download]  50.0% of 1MiB");
                    String template = request.getOption("-o");
                    Files.writeString(Path.of(template.replace(".%(ext)s", ".mp3").replace("%%", "%")), "audio");
                });
        return new DownloadService(extractor, downloader, processId -> killed.add(processId),
                new RoomTrackDao(database, library::notifyChanged), RoomPendingDownloadDao.of(database),
                database.executor, library, NetworkMonitor.alwaysOnline(), () -> true,
                () -> temp.resolve("music"), () -> "mp3");
    }

    private boolean finished(int count) {
        List<DownloadJob> jobs = service.getJobs();
        return jobs.size() == count && jobs.stream().noneMatch(DownloadJob::isActive);
    }

    private long pendingRows() {
        return RoomPendingDownloadDao.of(database).getAllSync().size();
    }

    private long pausedRows() {
        return RoomPendingDownloadDao.of(database).getAllSync().stream().filter(row -> row.paused).count();
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
