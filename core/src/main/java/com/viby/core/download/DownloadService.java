package com.viby.core.download;

import com.viby.core.data.LibraryRepository;
import com.viby.core.data.PendingDownload;
import com.viby.core.data.PlaylistSource;
import com.viby.core.data.RoomPendingDownloadDao;
import com.viby.core.data.RoomTrackDao;
import com.viby.core.data.Track;
import com.viby.core.util.StorageHelper;
import com.viby.core.util.YoutubeUrlParser;
import com.viby.core.ytdlp.VideoInfo;
import com.viby.core.ytdlp.YtDlpRequest;
import com.viby.core.ytdlp.YtDlpResponse;

import org.jspecify.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of the Android {@code DownloadService}: a sequential, persistent queue of yt-dlp jobs.
 * The structure and rules are kept 1:1 (job/track states, retries, pause by killing yt-dlp,
 * yielding to a prioritized track, waiting for the network, placeholders, YouTube order).
 * Android plumbing is replaced: intents become methods, LiveData a listener,
 * ConnectivityManager a {@link NetworkMonitor}; notifications are not used on desktop.
 *
 * <p>Desktop additions: preferred audio format from settings, playlists pre-resolved through the
 * YouTube Data API ({@link #enqueueVideos}), "came from YouTube" markers when reordering
 * ({@link LibraryRepository#applySourceOrder}) and square-friendly thumbnails.
 */
public final class DownloadService {

    private static final Logger LOG = Logger.getLogger(DownloadService.class.getName());

    public interface Listener {
        /** Live job objects (fields are volatile), called on download/UI threads. */
        void onJobsChanged(List<DownloadJob> jobs);
    }

    public interface ProcessKiller {
        boolean destroyProcessById(String processId);
    }

    /** One video of a list resolved through the YouTube Data API; {@code durationMs} 0 = unknown. */
    public record VideoEntry(String videoId, String title, @Nullable String uploader, long durationMs) {
    }

    /** Android R.string.default_playlist; desktop keeps the name its users already have. */
    public static final String DEFAULT_PLAYLIST = "Синглы";

    private static final int ENGINE_WAIT_SECONDS = 60;
    /** Клиенты для повторной попытки: tv/mweb обходят ложное «Video unavailable». */
    private static final String ALT_PLAYER_CLIENTS = "default,tv,mweb";

    /** "45.2% of ~ 4.32MiB at ..." — вытаскиваем общий размер трека. */
    private static final Pattern SIZE_PATTERN =
            Pattern.compile("of\\s+~?\\s*([0-9.]+)(KiB|MiB|GiB)");

    private final List<DownloadJob> jobs = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "viby-downloads");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger pendingCount = new AtomicInteger(0);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private boolean dispatcherRunning;
    private volatile String activeProcessId;
    private volatile @Nullable DownloadJob activeJob;
    private volatile boolean shutdown;

    private final RoomTrackDao dao;
    private final DownloadQueueRepository queueRepository;
    private final Executor dbExecutor;
    private final YoutubeExtractor youtubeExtractor;
    private final AudioDownloader audioDownloader;
    private final LibraryImporter libraryImporter;
    private final LibraryRepository library;
    private final ProcessKiller processKiller;
    private final NetworkMonitor network;
    private final BooleanSupplier engineReady;
    private final Supplier<Path> musicDirectory;
    private final Supplier<String> preferredFormat;
    private volatile boolean networkAvailable = true;
    private long lastPublishMs;

    public DownloadService(YoutubeExtractor youtubeExtractor, AudioDownloader audioDownloader,
                           ProcessKiller processKiller, RoomTrackDao dao,
                           RoomPendingDownloadDao pendingDao, Executor dbExecutor,
                           LibraryRepository library, NetworkMonitor network,
                           BooleanSupplier engineReady, Supplier<Path> musicDirectory,
                           Supplier<String> preferredFormat) {
        this.youtubeExtractor = youtubeExtractor;
        this.audioDownloader = audioDownloader;
        this.processKiller = processKiller;
        this.dao = dao;
        this.queueRepository = new DownloadQueueRepository(pendingDao);
        this.dbExecutor = dbExecutor;
        this.libraryImporter = LibraryImporter.create(dao);
        this.library = library;
        this.network = network;
        this.engineReady = engineReady;
        this.musicDirectory = musicDirectory;
        this.preferredFormat = preferredFormat;
        network.addListener(available -> refreshNetworkState());
        networkAvailable = network.isAvailable();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public List<DownloadJob> getJobs() {
        synchronized (jobs) {
            return new ArrayList<>(jobs);
        }
    }

    /** Android: playlist-only URLs, Mixes and watch URLs that carry a list. */
    public static boolean isPlaylistUrl(String url) {
        return YoutubeUrlParser.playlistId(url) != null;
    }

    // ------------------------------------------------------------ "intents"

    public void enqueue(String url, @Nullable String playlist, boolean isPlaylist) {
        enqueue(url, playlist, isPlaylist, 0L, -1L, false, null, null);
    }

    /**
     * Desktop: queue videos of a playlist already listed through the YouTube Data API, e.g. a
     * private playlist that yt-dlp cannot open without browser cookies.
     */
    public void enqueueVideos(String sourceUrl, String playlist, String title, List<VideoEntry> videos) {
        JSONArray entries = new JSONArray();
        for (VideoEntry video : videos) {
            entries.put(new JSONObject().put("id", video.videoId()).put("title", video.title())
                    .put("uploader", video.uploader()).put("duration", video.durationMs() / 1000.0));
        }
        JSONObject root = new JSONObject().put("title", title).put("entries", entries);
        enqueue(sourceUrl, playlist, true, 0L, -1L, false, null, root.toString());
    }

    /** Восстановить незавершённые загрузки после перезапуска. */
    public void restorePending() {
        dbExecutor.execute(() -> {
            for (PendingDownload pending : queueRepository.loadAll()) {
                enqueue(pending.url, pending.playlistName, pending.isPlaylist, pending.id,
                        pending.queuePosition, pending.paused, pending.trackOrderJson,
                        pending.entriesJson);
            }
        });
    }

    private void enqueue(String url, @Nullable String playlist, boolean isPlaylist, long pendingId,
                         long restoredPosition, boolean restoredPaused,
                         @Nullable String restoredTrackOrder, @Nullable String entriesJson) {
        if (shutdown || url == null || url.trim().isEmpty() || hasActiveJob(url.trim(), playlist)) {
            return;
        }
        DownloadJob job;
        synchronized (jobs) {
            job = DownloadJobFactory.create(
                    url, playlist, isPlaylist, pendingId,
                    restoredPosition, restoredPaused, restoredTrackOrder,
                    DownloadScheduler.nextQueuePosition(jobs));
            job.entriesJson = entriesJson;
            jobs.add(job);
            DownloadScheduler.sort(jobs);
        }
        if (playlist != null && !playlist.isEmpty()) {
            library.ensurePlaylist(playlist);
        }
        enqueuePreparedJob(job, pendingId == 0);
    }

    public void pause(long jobId) {
        setJobPaused(jobId, true);
    }

    public void resume(long jobId) {
        setJobPaused(jobId, false);
    }

    public void cancel(long jobId) {
        cancelJob(jobId);
    }

    public void moveJob(long jobId, long targetJobId) {
        moveJobInternal(jobId, targetJobId);
    }

    public void moveTrack(long jobId, String videoId, String targetVideoId) {
        moveTrackInternal(jobId, videoId, targetVideoId);
    }

    public void prioritizeTrack(String playlist, String videoId) {
        prioritizeTrackInternal(playlist, videoId);
    }

    public void retryTrack(long jobId, String videoId) {
        retryFailedInternal(jobId, videoId, false);
    }

    public void retryAllFailed(long jobId) {
        retryFailedInternal(jobId, null, true);
    }

    public void removeFailedTrack(long jobId, String videoId) {
        removeFailedInternal(jobId, videoId);
    }

    /** Recreates a durable single-track job from a failed placeholder after a restart. */
    public void retryPersistedTrack(Track track) {
        if (track.sourceUrl != null && !track.sourceUrl.isEmpty()) {
            enqueue(track.sourceUrl, track.playlistName, false);
        }
    }

    /** Desktop: removes finished jobs from the list (Android dismisses them with the notification). */
    public void clearFinished() {
        synchronized (jobs) {
            jobs.removeIf(job -> !job.isActive());
        }
        publish(true);
    }

    public void shutdown() {
        shutdown = true;
        DownloadJob job = activeJob;
        if (job != null) {
            // stopping is a pause: the job and its .part file resume on the next start
            job.pauseInterrupted = true;
        }
        killActiveProcess();
        worker.shutdownNow();
        try {
            worker.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void enqueuePreparedJob(DownloadJob job, boolean persist) {
        if (persist) {
            persistPending(job);
        }
        pendingCount.incrementAndGet();
        publish(true);
        scheduleDispatcher();
    }

    // ---------------------------------------------------------------- queue

    private void scheduleDispatcher() {
        synchronized (jobs) {
            if (dispatcherRunning || shutdown) {
                return;
            }
            dispatcherRunning = true;
        }
        worker.execute(this::dispatchJobs);
    }

    private void dispatchJobs() {
        while (true) {
            DownloadJob next;
            synchronized (jobs) {
                next = shutdown ? null : DownloadScheduler.nextRunnable(jobs);
                if (next == null) {
                    dispatcherRunning = false;
                    return;
                }
            }
            runJob(next);
        }
    }

    private void runJob(DownloadJob job) {
        activeJob = job;
        boolean terminal = false;
        try {
            if (job.cancelRequested) {
                job.status = DownloadJob.Status.CANCELED;
                terminal = true;
                return;
            }
            throwIfPaused(job);
            waitForEngine();
            throwIfPaused(job);
            if (job.isPlaylist) {
                runPlaylistJob(job);
            } else {
                runSingleJob(job);
            }
            terminal = job.status == DownloadJob.Status.DONE
                    || job.status == DownloadJob.Status.COMPLETED_WITH_ERRORS
                    || job.status == DownloadJob.Status.CANCELED;
        } catch (Exception e) {
            if (job.cancelRequested) {
                job.status = DownloadJob.Status.CANCELED;
                terminal = true;
            } else if (job.pauseInterrupted || e instanceof JobPausedException) {
                job.pauseInterrupted = false;
                job.status = job.pauseRequested
                        ? DownloadJob.Status.PAUSED : DownloadJob.Status.QUEUED;
            } else if (job.yieldInterrupted || e instanceof JobYieldException) {
                job.yieldInterrupted = false;
                job.status = DownloadJob.Status.QUEUED;
            } else {
                LOG.log(Level.WARNING, "job failed: " + job.url, e);
                job.status = DownloadJob.Status.FAILED;
                job.error = e instanceof ClassifiedDownloadException
                        ? failureMessage(((ClassifiedDownloadException) e).failure.code)
                        : shortError(e);
                terminal = true;
            }
        } finally {
            activeJob = null;
            if (!terminal) {
                persistPaused(job);
            }
            removePending(job); // задание завершилось — восстанавливать больше нечего
            publish(true);
            if (terminal && job.status == DownloadJob.Status.CANCELED) {
                cleanupPendingPlaceholders(job);
            }
        }
    }

    private static final class JobPausedException extends Exception {
    }

    private static final class JobYieldException extends Exception {
    }

    private static final class ClassifiedDownloadException extends Exception {
        final DownloadFailure failure;

        ClassifiedDownloadException(DownloadFailure failure, Throwable cause) {
            super(cause);
            this.failure = failure;
        }
    }

    public boolean hasActiveJob(String url, @Nullable String playlist) {
        synchronized (jobs) {
            for (DownloadJob job : jobs) {
                if (job.isActive() && job.url.equals(url)
                        && java.util.Objects.equals(job.playlistName, playlist)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void persistPending(DownloadJob job) {
        long createdAt = System.currentTimeMillis();
        dbExecutor.execute(() -> queueRepository.insert(job, createdAt));
    }

    private void removePending(DownloadJob job) {
        synchronized (job) {
            if (job.completionHandled || job.isActive()) {
                return;
            }
            job.completionHandled = true;
        }
        // dbExecutor последовательный: insert из persistPending выполнится раньше
        dbExecutor.execute(() -> queueRepository.delete(job.pendingId));
        pendingCount.decrementAndGet();
    }

    private void persistPaused(DownloadJob job) {
        dbExecutor.execute(() -> queueRepository.updatePaused(job));
    }

    private void persistQueueOrder() {
        List<DownloadJob> snapshot;
        synchronized (jobs) {
            DownloadScheduler.normalizeActivePositions(jobs);
            snapshot = new ArrayList<>(jobs);
        }
        dbExecutor.execute(() -> {
            for (DownloadJob job : snapshot) {
                queueRepository.updateQueuePosition(job);
            }
        });
    }

    private void runSingleJob(DownloadJob job) throws Exception {
        job.status = DownloadJob.Status.PREPARING;
        publish(true);

        YtDlpRequest infoRequest = youtubeExtractor.videoInfoRequest(job.url);
        VideoInfo info = fetchVideoInfo(job, infoRequest);
        String videoId = info.getId();
        String title = info.getTitle() != null ? info.getTitle() : job.url;
        job.title = title;
        job.currentTrackTitle = title;
        String playlist = job.playlistName != null && !job.playlistName.isEmpty()
                ? job.playlistName : DEFAULT_PLAYLIST;
        job.playlistName = playlist;
        library.ensurePlaylist(playlist);
        publish(true);

        if (libraryImporter.reuseDownloadedTrack(videoId, title, info.getUploader(),
                info.getDuration() * 1000L, playlist, null, job.url)) {
            job.status = DownloadJob.Status.DONE;
            return;
        }

        long durationMs = info.getDuration() * 1000L;
        DownloadJob.TrackItem item = findTrack(job, videoId);
        if (item == null) {
            item = new DownloadJob.TrackItem(videoId, title,
                    info.getUploader(), durationMs, 0);
            job.tracks.add(item);
        }
        ensurePlaceholder(item, playlist, job.url);
        job.activeTrack = item;
        try {
            downloadTrackWithRetries(job, item, job.url, videoId, title,
                    info.getUploader(), durationMs, playlist, null);
        } finally {
            job.activeTrack = null;
        }
        job.status = job.cancelRequested
                ? DownloadJob.Status.CANCELED : DownloadJob.Status.DONE;
    }

    private void runPlaylistJob(DownloadJob job) throws Exception {
        job.status = DownloadJob.Status.PREPARING;
        publish(true);

        JSONObject root;
        if (job.entriesJson != null) {
            // desktop: entries were resolved through the YouTube Data API
            root = new JSONObject(job.entriesJson);
        } else if (job.cachedInfoJson != null) {
            root = new JSONObject(job.cachedInfoJson);
        } else {
            YtDlpRequest infoRequest = youtubeExtractor.playlistInfoRequest(
                    job.url, YoutubeUrlParser.isRadioMixUrl(job.url));
            String processId = "job-" + job.id + "-info";
            activeProcessId = processId;
            YtDlpResponse response = fetchPlaylistInfo(job, infoRequest, processId);
            root = new JSONObject(response.out());
            job.cachedInfoJson = response.out();
        }

        String playlistTitle = root.optString("title", "Playlist");
        String playlist = job.playlistName != null && !job.playlistName.isEmpty()
                ? job.playlistName : StorageHelper.sanitize(playlistTitle);
        job.playlistName = playlist;
        job.title = playlistTitle;
        library.ensurePlaylist(playlist);

        // запоминаем источник — для кнопки «обновить плейлист»
        PlaylistSource source = library.source(playlist);
        if (source == null) {
            source = new PlaylistSource();
        }
        source.playlistName = playlist;
        source.sourceUrl = job.url;
        if (source.youtubePlaylistId == null) {
            source.youtubePlaylistId = YoutubeUrlParser.playlistId(job.url);
        }
        source.updatedAt = System.currentTimeMillis();
        library.saveSource(source);

        JSONArray entries = root.optJSONArray("entries");
        if (entries == null || entries.length() == 0) {
            throw new IllegalStateException("В плейлисте не нашлось видео");
        }
        job.totalCount = entries.length();
        publish(true);

        java.util.Map<String, Integer> youtubePositions = new java.util.HashMap<>();
        for (int i = 0; i < entries.length(); i++) {
            String videoId = entries.getJSONObject(i).optString("id", "");
            if (!videoId.isEmpty()) {
                youtubePositions.put(videoId, i);
            }
        }
        prepareTrackQueue(job, entries);
        ensurePlaceholderTracks(job, playlist);
        applyYoutubeOrder(playlist, youtubePositions);

        while (true) {
            if (job.cancelRequested) {
                break;
            }
            waitWhilePaused(job);
            if (job.cancelRequested) {
                break;
            }
            DownloadJob.TrackItem item = nextTrack(job);
            if (item == null) {
                break;
            }
            item.status = DownloadJob.TrackItem.Status.DOWNLOADING;
            item.progress = 0;
            job.activeTrack = item;
            job.currentIndex = completedTrackCount(job) + 1;
            job.progress = 0;
            job.downloadedBytes = 0;
            job.totalBytes = 0;
            job.currentTrackTitle = item.title;
            publish(true);
            try {
                if (item.videoId.isEmpty() || libraryImporter.reuseDownloadedTrack(item.videoId,
                        item.title, item.uploader, item.durationMs, playlist,
                        item.youtubePosition, "https://www.youtube.com/watch?v="
                                + item.videoId)) {
                    item.progress = 100;
                    item.status = DownloadJob.TrackItem.Status.DOWNLOADED;
                    continue;
                }
                String videoUrl = "https://www.youtube.com/watch?v=" + item.videoId;
                downloadTrackWithRetries(job, item, videoUrl, item.videoId,
                        item.title, item.uploader, item.durationMs, playlist,
                        item.youtubePosition);
                item.progress = 100;
                item.status = DownloadJob.TrackItem.Status.DOWNLOADED;
            } catch (Exception e) {
                if (job.cancelRequested) {
                    item.status = DownloadJob.TrackItem.Status.WAITING;
                    break;
                }
                if (job.pauseRequested || job.pauseInterrupted || job.yieldInterrupted
                        || e instanceof JobPausedException || e instanceof JobYieldException) {
                    item.status = DownloadJob.TrackItem.Status.WAITING;
                    throw e;
                }
                if (item.status != DownloadJob.TrackItem.Status.FAILED_TEMPORARY
                        && item.status != DownloadJob.TrackItem.Status.FAILED_PERMANENT) {
                    DownloadFailure failure = e instanceof ClassifiedDownloadException
                            ? ((ClassifiedDownloadException) e).failure
                            : DownloadFailure.classify(e);
                    setFailedState(item, failure);
                    persistTrackState(playlist, item);
                }
                LOG.log(Level.INFO, "playlist entry failed [" + item.failureCode + "]: " + item.title, e);
                job.failedCount++;
                job.failedTitles.add(item.title);
            } finally {
                if (job.activeTrack == item) {
                    job.activeTrack = null;
                }
            }
        }
        applyYoutubeOrder(playlist, youtubePositions);
        job.status = job.cancelRequested
                ? DownloadJob.Status.CANCELED
                : job.failedCount > 0
                ? DownloadJob.Status.COMPLETED_WITH_ERRORS
                : DownloadJob.Status.DONE;
    }

    private void prepareTrackQueue(DownloadJob job, JSONArray entries) throws Exception {
        synchronized (job.tracks) {
            if (!job.tracks.isEmpty()) {
                return;
            }
            java.util.Map<String, DownloadJob.TrackItem> byId =
                    new java.util.LinkedHashMap<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                String videoId = entry.optString("id", "");
                if (videoId.isEmpty()) {
                    continue;
                }
                String title = entry.optString("title", videoId);
                String uploader = entry.optString("uploader",
                        entry.optString("channel", null));
                long durationMs = (long) (entry.optDouble("duration", 0) * 1000);
                byId.put(videoId, new DownloadJob.TrackItem(
                        videoId, title, uploader, durationMs, i));
            }
            if (job.trackOrderJson != null && !job.trackOrderJson.isEmpty()) {
                JSONArray restoredOrder = new JSONArray(job.trackOrderJson);
                for (int i = 0; i < restoredOrder.length(); i++) {
                    DownloadJob.TrackItem restored = byId.remove(
                            restoredOrder.optString(i, ""));
                    if (restored != null) {
                        job.tracks.add(restored);
                    }
                }
            }
            job.tracks.addAll(byId.values());
            job.trackOrderJson = serializeTrackOrderLocked(job);
        }
        persistTrackOrder(job);
        publish(true);
    }

    private DownloadJob.@Nullable TrackItem nextTrack(DownloadJob job) {
        synchronized (job.tracks) {
            for (DownloadJob.TrackItem item : job.tracks) {
                if (item.status == DownloadJob.TrackItem.Status.WAITING) {
                    return item;
                }
            }
        }
        return null;
    }

    private int completedTrackCount(DownloadJob job) {
        int count = 0;
        synchronized (job.tracks) {
            for (DownloadJob.TrackItem item : job.tracks) {
                if (item.status == DownloadJob.TrackItem.Status.DOWNLOADED
                        || item.status == DownloadJob.TrackItem.Status.FAILED_TEMPORARY
                        || item.status == DownloadJob.TrackItem.Status.FAILED_PERMANENT) {
                    count++;
                }
            }
        }
        return count;
    }

    private void ensurePlaceholderTracks(DownloadJob job, String playlist) {
        synchronized (job.tracks) {
            for (DownloadJob.TrackItem item : job.tracks) {
                String sourceUrl = "https://www.youtube.com/watch?v=" + item.videoId;
                if (libraryImporter.reuseDownloadedTrack(item.videoId, item.title, item.uploader,
                        item.durationMs, playlist, item.youtubePosition, sourceUrl)) {
                    item.status = DownloadJob.TrackItem.Status.DOWNLOADED;
                    continue;
                }
                Track existing = dao.getByVideoIdSync(playlist, item.videoId);
                if (existing != null) {
                    existing.title = item.title;
                    existing.uploader = item.uploader;
                    existing.durationMs = item.durationMs;
                    existing.youtubePosition = item.youtubePosition;
                    existing.thumbnailUrl = thumbnailUrl(item.videoId);
                    existing.sourceUrl = sourceUrl;
                    existing.downloaded = false;
                    existing.filePath = "";
                    boolean stillFailed = item.status
                            == DownloadJob.TrackItem.Status.FAILED_TEMPORARY
                            || item.status == DownloadJob.TrackItem.Status.FAILED_PERMANENT;
                    if (!stillFailed) {
                        existing.downloadState = Track.DOWNLOAD_WAITING;
                        existing.downloadErrorCode = null;
                        existing.downloadErrorMessage = null;
                        existing.downloadProgress = 0;
                    }
                    dao.update(existing);
                    continue;
                }
                Track placeholder = new Track();
                placeholder.videoId = item.videoId;
                placeholder.title = item.title;
                placeholder.uploader = item.uploader;
                placeholder.durationMs = item.durationMs;
                placeholder.playlistName = playlist;
                placeholder.thumbnailUrl = thumbnailUrl(item.videoId);
                placeholder.position = dao.nextPosition(playlist);
                placeholder.youtubePosition = item.youtubePosition;
                placeholder.createdAt = System.currentTimeMillis();
                placeholder.downloaded = false;
                placeholder.sourceUrl = sourceUrl;
                placeholder.downloadState = Track.DOWNLOAD_WAITING;
                dao.insert(placeholder);
            }
        }
    }

    private void ensurePlaceholder(DownloadJob.TrackItem item, String playlist,
                                   String sourceUrl) {
        Track existing = dao.getByVideoIdSync(playlist, item.videoId);
        if (existing == null) {
            existing = new Track();
            existing.videoId = item.videoId;
            existing.playlistName = playlist;
            existing.position = dao.nextPosition(playlist);
            existing.createdAt = System.currentTimeMillis();
        }
        existing.title = item.title;
        existing.uploader = item.uploader;
        existing.durationMs = item.durationMs;
        existing.thumbnailUrl = item.videoId != null && !item.videoId.isEmpty()
                ? thumbnailUrl(item.videoId) : null;
        existing.sourceUrl = sourceUrl;
        existing.filePath = "";
        existing.downloaded = false;
        existing.downloadState = Track.DOWNLOAD_WAITING;
        existing.downloadErrorCode = null;
        existing.downloadErrorMessage = null;
        existing.downloadProgress = 0;
        if (existing.id == 0) {
            dao.insert(existing);
        } else {
            dao.update(existing);
        }
    }

    private void persistTrackState(String playlist, DownloadJob.TrackItem item) {
        Track track = dao.getByVideoIdSync(playlist, item.videoId);
        if (track == null || track.downloaded) {
            return;
        }
        switch (item.status) {
            case DOWNLOADING -> track.downloadState = Track.DOWNLOAD_DOWNLOADING;
            case RETRY_SCHEDULED -> track.downloadState = Track.DOWNLOAD_RETRY_SCHEDULED;
            case FAILED_TEMPORARY -> track.downloadState = Track.DOWNLOAD_FAILED_TEMPORARY;
            case FAILED_PERMANENT -> track.downloadState = Track.DOWNLOAD_FAILED_PERMANENT;
            case DOWNLOADED -> track.downloadState = Track.DOWNLOAD_DOWNLOADED;
            default -> track.downloadState = Track.DOWNLOAD_WAITING;
        }
        track.downloadErrorCode = item.failureCode;
        track.downloadErrorMessage = item.failureMessage;
        track.downloadAttemptCount = item.attemptCount;
        track.downloadLastAttemptAt = item.lastAttemptAt;
        track.downloadFormat = item.selectedFormat;
        track.downloadRequiresAuthorization = item.requiresAuthorization;
        track.downloadProgress = item.progress;
        dao.update(track);
    }

    private void setFailedState(DownloadJob.TrackItem item, DownloadFailure failure) {
        item.status = failure.retryable
                ? DownloadJob.TrackItem.Status.FAILED_TEMPORARY
                : DownloadJob.TrackItem.Status.FAILED_PERMANENT;
        item.failureCode = failure.code.name();
        item.failureMessage = failureMessage(failure.code);
        item.requiresAuthorization = failure.requiresAuthorization;
        item.nextRetryAt = 0L;
    }

    private void waitForRetry(DownloadJob job, long delayMs) throws Exception {
        long deadline = System.currentTimeMillis() + delayMs;
        while (System.currentTimeMillis() < deadline) {
            throwIfPaused(job);
            if (job.cancelRequested) {
                throw new InterruptedException("canceled");
            }
            long remaining = deadline - System.currentTimeMillis();
            try {
                Thread.sleep(Math.min(250L, Math.max(1L, remaining)));
            } catch (InterruptedException interrupted) {
                if (job.cancelRequested || shutdown) {
                    throw interrupted;
                }
            }
        }
    }

    /** Android download_error_* strings (values-ru). */
    public static String failureMessage(DownloadFailure.Code code) {
        return switch (code) {
            case VIDEO_REMOVED -> "Видео удалено или заблокировано правообладателем";
            case VIDEO_UNAVAILABLE -> "YouTube сообщает, что видео недоступно";
            case VIDEO_PRIVATE -> "Видео приватное или доступно только участникам";
            case PLAYLIST_UNAVAILABLE -> "Плейлист не найден или он приватный — войдите в аккаунт YouTube";
            case REGION_BLOCKED -> "Видео недоступно в этом регионе";
            case AGE_RESTRICTED -> "Нужно подтвердить возраст или войти в YouTube";
            case EXTRACTOR_OUTDATED -> "yt-dlp не может прочитать видео — попробуйте обновить yt-dlp";
            case AUTH_REQUIRED -> "Нужен вход в YouTube или свежие cookies";
            case RATE_LIMITED -> "YouTube временно ограничил запросы — повторите позже";
            case NETWORK -> "Соединение с сетью было прервано";
            case FFMPEG -> "FFmpeg не смог преобразовать аудио";
            case STORAGE_FULL -> "Недостаточно свободного места";
            case FILE_SYSTEM -> "Не удалось создать или записать аудиофайл";
            case FORMAT_UNAVAILABLE -> "YouTube не предоставил поддерживаемый аудиоформат";
            default -> "Неизвестная ошибка загрузки";
        };
    }

    /** Desktop variant of applyYoutubeOrder, see {@link LibraryRepository#applySourceOrder}. */
    private void applyYoutubeOrder(String playlist,
                                   java.util.Map<String, Integer> youtubePositions) {
        library.applySourceOrder(playlist, youtubePositions);
    }

    // ------------------------------------------------------------- download

    private void downloadTrackWithRetries(DownloadJob job, DownloadJob.TrackItem item,
                                          String videoUrl, String videoId,
                                          String title, @Nullable String uploader,
                                          long durationMs, String playlist,
                                          @Nullable Integer youtubePosition) throws Exception {
        int transientAttempts = 0;
        int networkAttempts = 0;
        while (true) {
            throwIfPaused(job);
            item.status = DownloadJob.TrackItem.Status.DOWNLOADING;
            item.attemptCount++;
            item.lastAttemptAt = System.currentTimeMillis();
            item.nextRetryAt = 0L;
            item.failureCode = null;
            item.failureMessage = null;
            item.requiresAuthorization = false;
            persistTrackState(playlist, item);
            publish(true);
            try {
                downloadTrack(job, videoUrl, videoId, title, uploader, durationMs,
                        playlist, youtubePosition, item);
                clearNetworkRetry(job);
                item.status = DownloadJob.TrackItem.Status.DOWNLOADED;
                item.progress = 100;
                persistTrackState(playlist, item);
                return;
            } catch (Exception error) {
                if (job.cancelRequested || job.pauseRequested || job.pauseInterrupted
                        || job.yieldInterrupted || error instanceof JobPausedException
                        || error instanceof JobYieldException) {
                    throw error;
                }
                DownloadFailure failure = error instanceof ClassifiedDownloadException
                        ? ((ClassifiedDownloadException) error).failure
                        : DownloadFailure.classify(error);
                item.failureCode = failure.code.name();
                item.failureMessage = failureMessage(failure.code);
                item.requiresAuthorization = failure.requiresAuthorization;
                if (failure.code == DownloadFailure.Code.NETWORK) {
                    item.status = DownloadJob.TrackItem.Status.RETRY_SCHEDULED;
                    item.nextRetryAt = System.currentTimeMillis()
                            + DownloadRetryPolicy.delayAfterAttempt(++networkAttempts);
                    persistTrackState(playlist, item);
                    waitForNetworkRetry(job, networkAttempts);
                    continue;
                }
                transientAttempts++;
                if (!DownloadRetryPolicy.shouldRetry(failure, transientAttempts)) {
                    setFailedState(item, failure);
                    persistTrackState(playlist, item);
                    throw new ClassifiedDownloadException(failure, error);
                }
                long delayMs = DownloadRetryPolicy.delayAfterAttempt(transientAttempts);
                item.status = DownloadJob.TrackItem.Status.RETRY_SCHEDULED;
                item.nextRetryAt = System.currentTimeMillis() + delayMs;
                persistTrackState(playlist, item);
                publish(true);
                waitForRetry(job, delayMs);
            }
        }
    }

    private void downloadTrack(DownloadJob job, String videoUrl, String videoId,
                               String title, @Nullable String uploader,
                               long durationMs, String playlist,
                               @Nullable Integer youtubePosition,
                               DownloadJob.TrackItem item) throws Exception {
        job.status = DownloadJob.Status.DOWNLOADING;
        job.currentTrackTitle = title;
        job.progress = 0;
        job.downloadedBytes = 0;
        job.totalBytes = 0;
        File dir = StorageHelper.playlistDir(musicDirectory.get(), playlist);
        StorageHelper.requireSpace(dir.toPath(), durationMs);
        String baseName = StorageHelper.sanitize(title);
        // защита от одинаковых названий разных видео
        if (new File(dir, baseName + ".mp3").exists()
                || new File(dir, baseName + ".m4a").exists()) {
            baseName = baseName + " [" + videoId + "]";
        }

        // desktop: the first attempts use the format chosen in settings (Android: always mp3)
        String format = preferredFormat.get();
        File resultFile;
        try {
            item.selectedFormat = format;
            resultFile = downloadWithPauseRetry(job, videoUrl, dir, baseName, format, null);
        } catch (Exception e) {
            if (job.cancelRequested || job.pauseRequested || job.pauseInterrupted
                    || job.yieldInterrupted || e instanceof JobPausedException
                    || e instanceof JobYieldException) {
                throw e;
            }
            DownloadFailure firstFailure = DownloadFailure.classify(e);
            if (!shouldTryAlternatePlayerClients(firstFailure)) {
                throw e;
            }
            // Многие «Video unavailable» — это блокировка дефолтного клиента yt-dlp,
            // а не мёртвое видео. Пробуем другие player-client (tv/mweb их обходят).
            // Реально удалённые/заблокированные видео повторять смысла нет.
            if (isPermanentlyUnavailable(e)) {
                throw e;
            }
            LOG.log(Level.INFO, "retrying with alternate player clients: " + title, e);
            try {
                item.selectedFormat = format;
                resultFile = downloadWithPauseRetry(job, videoUrl, dir, baseName,
                        format, ALT_PLAYER_CLIENTS);
            } catch (Exception e2) {
                if (job.cancelRequested || job.pauseRequested || job.pauseInterrupted
                        || job.yieldInterrupted || e2 instanceof JobPausedException
                        || e2 instanceof JobYieldException) {
                    throw e2;
                }
                // последний шанс: другой контейнер + альтернативные клиенты
                LOG.log(Level.INFO, format + " failed, retrying as m4a: " + title, e2);
                item.selectedFormat = "m4a";
                resultFile = downloadWithPauseRetry(job, videoUrl, dir, baseName,
                        "m4a", ALT_PLAYER_CLIENTS);
            }
        }

        libraryImporter.importDownloaded(videoId, title, uploader, durationMs,
                playlist, youtubePosition, videoUrl, resultFile, item.attemptCount,
                item.lastAttemptAt, item.selectedFormat);
    }

    private static boolean shouldTryAlternatePlayerClients(DownloadFailure failure) {
        return failure.code == DownloadFailure.Code.UNKNOWN
                || failure.code == DownloadFailure.Code.VIDEO_UNAVAILABLE
                || failure.code == DownloadFailure.Code.FORMAT_UNAVAILABLE;
    }

    /** Пауза = убить процесс yt-dlp; при возобновлении он докачивает .part-файл. */
    private File downloadWithPauseRetry(DownloadJob job, String videoUrl, File dir,
                                        String baseName, String format,
                                        @Nullable String playerClients) throws Exception {
        while (true) {
            waitWhilePaused(job);
            waitForNetwork(job);
            if (job.cancelRequested) {
                throw new InterruptedException("canceled");
            }
            job.networkInterrupted = false;
            try {
                File result = executeDownload(
                        job, videoUrl, dir, baseName, format, playerClients);
                job.networkInterrupted = false;
                return result;
            } catch (Exception e) {
                if (job.pauseRequested && !job.cancelRequested) {
                    continue; // процесс убит паузой — подождём и продолжим
                }
                if (!job.cancelRequested
                        && (job.networkInterrupted || !networkAvailable)) {
                    waitForNetwork(job);
                    continue;
                }
                throw e;
            }
        }
    }

    private VideoInfo fetchVideoInfo(DownloadJob job, YtDlpRequest request)
            throws Exception {
        int attempt = 0;
        while (true) {
            waitForNetwork(job);
            throwIfPaused(job);
            job.networkInterrupted = false;
            try {
                VideoInfo info = youtubeExtractor.getVideoInfo(request);
                job.networkInterrupted = false;
                clearNetworkRetry(job);
                return info;
            } catch (Exception error) {
                if (job.cancelRequested || job.pauseRequested || job.pauseInterrupted
                        || job.yieldInterrupted || error instanceof JobPausedException
                        || error instanceof JobYieldException) {
                    throw error;
                }
                DownloadFailure failure = DownloadFailure.classify(error);
                boolean networkFailure = job.networkInterrupted || !networkAvailable
                        || failure.code == DownloadFailure.Code.NETWORK;
                if (!networkFailure) {
                    throw new ClassifiedDownloadException(failure, error);
                }
                waitForNetworkRetry(job, ++attempt);
            }
        }
    }

    private YtDlpResponse fetchPlaylistInfo(DownloadJob job,
                                            YtDlpRequest request,
                                            String processId) throws Exception {
        int attempt = 0;
        while (true) {
            waitForNetwork(job);
            throwIfPaused(job);
            job.networkInterrupted = false;
            activeProcessId = processId;
            try {
                YtDlpResponse response = youtubeExtractor.getPlaylistInfo(
                        request, processId);
                job.networkInterrupted = false;
                clearNetworkRetry(job);
                return response;
            } catch (Exception error) {
                if (job.cancelRequested || job.pauseRequested || job.pauseInterrupted
                        || job.yieldInterrupted || error instanceof JobPausedException
                        || error instanceof JobYieldException) {
                    throw error;
                }
                DownloadFailure failure = DownloadFailure.classify(error);
                boolean networkFailure = job.networkInterrupted || !networkAvailable
                        || failure.code == DownloadFailure.Code.NETWORK;
                if (!networkFailure) {
                    throw new ClassifiedDownloadException(failure, error);
                }
                waitForNetworkRetry(job, ++attempt);
            } finally {
                activeProcessId = null;
            }
        }
    }

    private void waitForNetworkRetry(DownloadJob job, int attempt)
            throws Exception {
        job.networkRetrying = true;
        publish(true);
        long delayMs = DownloadRetryPolicy.delayAfterAttempt(attempt);
        long deadline = System.currentTimeMillis() + delayMs;
        while (System.currentTimeMillis() < deadline) {
            throwIfPaused(job);
            if (job.cancelRequested) {
                throw new InterruptedException("canceled");
            }
            if (!networkAvailable) {
                waitForNetwork(job);
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0L) {
                Thread.sleep(Math.min(250L, remaining));
            }
        }
    }

    private void clearNetworkRetry(DownloadJob job) {
        if (!job.networkRetrying) {
            return;
        }
        job.networkRetrying = false;
        publish(true);
    }

    private void waitWhilePaused(DownloadJob job)
            throws JobPausedException, JobYieldException {
        if (job.yieldInterrupted && !job.cancelRequested) {
            throw new JobYieldException();
        }
        if (!job.pauseRequested || job.cancelRequested) {
            return;
        }
        job.status = DownloadJob.Status.PAUSED;
        publish(true);
        throw new JobPausedException();
    }

    private void throwIfPaused(DownloadJob job)
            throws JobPausedException, JobYieldException {
        if (job.yieldInterrupted && !job.cancelRequested) {
            throw new JobYieldException();
        }
        if ((job.pauseRequested || job.pauseInterrupted) && !job.cancelRequested) {
            throw new JobPausedException();
        }
    }

    private File executeDownload(DownloadJob job, String videoUrl, File dir,
                                 String baseName, String format,
                                 @Nullable String playerClients) throws Exception {
        String processId = "job-" + job.id;
        activeProcessId = processId;
        try {
            return audioDownloader.download(
                    videoUrl, dir, baseName, format, playerClients, processId,
                    (progress, etaSeconds, line) -> {
                        job.progress = DownloadProgressParser.resolve(
                                job.progress, progress, line);
                        DownloadJob.TrackItem activeTrack = job.activeTrack;
                        if (activeTrack != null) {
                            activeTrack.progress = job.progress;
                            if (job.playlistName != null
                                    && (activeTrack.progress == 100
                                    || activeTrack.progress
                                    >= activeTrack.persistedProgress + 5)) {
                                activeTrack.persistedProgress = activeTrack.progress;
                                persistTrackState(job.playlistName, activeTrack);
                            }
                        }
                        parseSizes(job, line);
                        publish(false);
                    });
        } finally {
            activeProcessId = null;
        }
    }

    static void parseSizes(DownloadJob job, @Nullable String line) {
        if (line == null) {
            return;
        }
        Matcher matcher = SIZE_PATTERN.matcher(line);
        if (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1));
                switch (matcher.group(2)) {
                    case "GiB" -> value *= 1024 * 1024 * 1024;
                    case "MiB" -> value *= 1024 * 1024;
                    default -> value *= 1024;
                }
                job.totalBytes = (long) value;
                job.downloadedBytes = (long) (value * job.progress / 100.0);
            } catch (NumberFormatException ignored) {
                // a malformed size line only hides the byte counter
            }
        }
    }

    private void waitForNetwork(DownloadJob job)
            throws InterruptedException, JobPausedException, JobYieldException {
        while (!networkAvailable) {
            throwIfPaused(job);
            if (job.cancelRequested) {
                throw new InterruptedException("canceled");
            }
            if (!job.waitingForNetwork) {
                job.waitingForNetwork = true;
                publish(true);
            }
            Thread.sleep(250L);
        }
        if (job.waitingForNetwork) {
            job.waitingForNetwork = false;
            publish(true);
        }
    }

    private void refreshNetworkState() {
        boolean available = network.isAvailable();
        boolean changed = networkAvailable != available;
        networkAvailable = available;
        DownloadJob job = activeJob;
        if (job != null && job.isActive()) {
            if (!available) {
                job.networkInterrupted = true;
            }
            boolean waitingChanged = job.waitingForNetwork == available;
            job.waitingForNetwork = !available;
            if (changed || waitingChanged) {
                publish(true);
            }
        }
    }

    // ------------------------------------------------------- pause / cancel

    private void setJobPaused(long jobId, boolean paused) {
        DownloadQueueActions.PauseResult result;
        synchronized (jobs) {
            result = DownloadQueueActions.setPaused(jobs, activeJob, jobId, paused);
        }
        if (!result.changed()) {
            return;
        }
        DownloadJob job = result.job;
        persistPaused(job);
        if (result.killActiveProcess) {
            killActiveProcess();
        }
        publish(true);
        scheduleDispatcher();
    }

    private void cancelJob(long jobId) {
        DownloadQueueActions.CancelResult result;
        synchronized (jobs) {
            result = DownloadQueueActions.cancel(jobs, activeJob, jobId);
        }
        if (!result.changed()) {
            return;
        }
        DownloadJob job = result.job;
        if (result.killActiveProcess) {
            killActiveProcess();
        }
        if (result.finishImmediately) {
            cleanupPendingPlaceholders(job);
            removePending(job);
        }
        publish(true);
        scheduleDispatcher();
    }

    private void moveJobInternal(long jobId, long targetJobId) {
        boolean changed;
        synchronized (jobs) {
            changed = DownloadQueueActions.moveJob(jobs, jobId, targetJobId);
        }
        if (!changed) {
            return;
        }
        persistQueueOrder();
        yieldIfOutranked();
        publish(true);
    }

    /**
     * Desktop: the topmost job always downloads. When a queued job is dragged above the running
     * one, the running job stops (back to QUEUED, resumable) and the dispatcher picks the new top.
     */
    private void yieldIfOutranked() {
        DownloadJob running = activeJob;
        if (running == null) {
            return;
        }
        boolean outranked = false;
        synchronized (jobs) {
            for (DownloadJob job : jobs) {
                if (job == running) {
                    break;
                }
                if (job.status == DownloadJob.Status.QUEUED
                        && !job.pauseRequested && !job.cancelRequested) {
                    outranked = true;
                    break;
                }
            }
        }
        if (outranked) {
            running.yieldInterrupted = true;
            killActiveProcess();
            scheduleDispatcher();
        }
    }

    private void retryFailedInternal(long jobId, @Nullable String videoId,
                                     boolean retryAll) {
        DownloadJob job = findJob(jobId);
        if (job == null || job.isActive()) {
            return;
        }
        boolean changed = false;
        synchronized (job.tracks) {
            for (DownloadJob.TrackItem item : job.tracks) {
                boolean failed = item.status == DownloadJob.TrackItem.Status.FAILED_TEMPORARY
                        || item.status == DownloadJob.TrackItem.Status.FAILED_PERMANENT;
                if (failed && (retryAll || item.videoId.equals(videoId))) {
                    item.status = DownloadJob.TrackItem.Status.WAITING;
                    item.progress = 0;
                    item.nextRetryAt = 0L;
                    changed = true;
                }
            }
            rebuildFailureSummary(job);
        }
        if (!changed) {
            return;
        }
        synchronized (jobs) {
            job.queuePosition = DownloadScheduler.nextQueuePosition(jobs);
            DownloadScheduler.sort(jobs);
        }
        job.error = null;
        job.cancelRequested = false;
        job.pauseRequested = false;
        job.completionHandled = false;
        job.pendingId = 0L;
        job.status = DownloadJob.Status.QUEUED;
        persistPending(job);
        pendingCount.incrementAndGet();
        publish(true);
        scheduleDispatcher();
    }

    private void removeFailedInternal(long jobId, @Nullable String videoId) {
        if (videoId == null) {
            return;
        }
        DownloadJob job = findJob(jobId);
        if (job == null || job.isActive()) {
            return;
        }
        boolean removed;
        synchronized (job.tracks) {
            removed = job.tracks.removeIf(item -> videoId.equals(item.videoId)
                    && (item.status == DownloadJob.TrackItem.Status.FAILED_TEMPORARY
                    || item.status == DownloadJob.TrackItem.Status.FAILED_PERMANENT));
            rebuildFailureSummary(job);
        }
        if (!removed) {
            return;
        }
        if (job.failedCount == 0 && job.status == DownloadJob.Status.COMPLETED_WITH_ERRORS) {
            job.status = DownloadJob.Status.DONE;
        }
        if (job.playlistName != null) {
            String playlist = job.playlistName;
            dbExecutor.execute(() -> dao.deletePending(playlist, videoId));
        }
        publish(true);
    }

    @Nullable
    private DownloadJob findJob(long jobId) {
        synchronized (jobs) {
            for (DownloadJob job : jobs) {
                if (job.id == jobId) {
                    return job;
                }
            }
        }
        return null;
    }

    private DownloadJob.@Nullable TrackItem findTrack(DownloadJob job, String videoId) {
        synchronized (job.tracks) {
            for (DownloadJob.TrackItem item : job.tracks) {
                if (videoId.equals(item.videoId)) {
                    return item;
                }
            }
        }
        return null;
    }

    private void rebuildFailureSummary(DownloadJob job) {
        job.failedCount = 0;
        job.failedTitles.clear();
        for (DownloadJob.TrackItem item : job.tracks) {
            if (item.status == DownloadJob.TrackItem.Status.FAILED_TEMPORARY
                    || item.status == DownloadJob.TrackItem.Status.FAILED_PERMANENT) {
                job.failedCount++;
                job.failedTitles.add(item.title);
            }
        }
    }

    private void moveTrackInternal(long jobId, @Nullable String videoId,
                                   @Nullable String targetVideoId) {
        if (videoId == null || targetVideoId == null || videoId.equals(targetVideoId)) {
            return;
        }
        DownloadJob job;
        synchronized (jobs) {
            job = findJobLocked(jobId);
        }
        if (job == null || !job.isPlaylist || !job.isActive()) {
            return;
        }
        boolean yieldActive = false;
        synchronized (job.tracks) {
            int from = indexOfTrackLocked(job, videoId);
            int to = indexOfTrackLocked(job, targetVideoId);
            if (from < 0 || to < 0) {
                return;
            }
            DownloadJob.TrackItem.Status moving = job.tracks.get(from).status;
            DownloadJob.TrackItem.Status target = job.tracks.get(to).status;
            if (moving == DownloadJob.TrackItem.Status.WAITING
                    && target == DownloadJob.TrackItem.Status.DOWNLOADING) {
                // desktop: dropped above the downloading track = download this one right now
                yieldActive = true;
            } else if (moving == DownloadJob.TrackItem.Status.DOWNLOADING
                    && target == DownloadJob.TrackItem.Status.WAITING) {
                // desktop: the downloading track dragged down waits after the target;
                // its partial file is resumed when its turn comes
                job.tracks.add(to, job.tracks.remove(from));
                job.trackOrderJson = serializeTrackOrderLocked(job);
            } else if (!DownloadQueueActions.moveTrack(job, videoId, targetVideoId)) {
                return;
            } else {
                job.trackOrderJson = serializeTrackOrderLocked(job);
            }
        }
        if (yieldActive) {
            prioritizeTrackInternal(job.playlistName, videoId);
            return;
        }
        persistTrackOrder(job);
        if (job == activeJob && findTrackStatus(job, videoId) == DownloadJob.TrackItem.Status.DOWNLOADING) {
            job.yieldInterrupted = true;
            killActiveProcess();
            scheduleDispatcher();
        }
        publish(true);
    }

    private DownloadJob.TrackItem.@Nullable Status findTrackStatus(DownloadJob job, String videoId) {
        DownloadJob.TrackItem item = findTrack(job, videoId);
        return item != null ? item.status : null;
    }

    private void prioritizeTrackInternal(@Nullable String playlist,
                                         @Nullable String videoId) {
        if (playlist == null || videoId == null) {
            return;
        }
        DownloadJob targetJob = null;
        synchronized (jobs) {
            for (DownloadJob candidate : jobs) {
                if (candidate.isPlaylist && candidate.isActive()
                        && playlist.equals(candidate.playlistName)) {
                    synchronized (candidate.tracks) {
                        if (indexOfTrackLocked(candidate, videoId) >= 0) {
                            targetJob = candidate;
                            break;
                        }
                    }
                }
            }
            if (targetJob == null) {
                return;
            }
            targetJob.pauseRequested = false;
            if (targetJob.status == DownloadJob.Status.PAUSED) {
                targetJob.status = DownloadJob.Status.QUEUED;
            }
            jobs.remove(targetJob);
            int firstActive = 0;
            while (firstActive < jobs.size() && !jobs.get(firstActive).isActive()) {
                firstActive++;
            }
            jobs.add(firstActive, targetJob);
        }

        boolean alreadyDownloading;
        synchronized (targetJob.tracks) {
            int from = indexOfTrackLocked(targetJob, videoId);
            if (from < 0) {
                return;
            }
            DownloadJob.TrackItem target = targetJob.tracks.get(from);
            alreadyDownloading = target.status == DownloadJob.TrackItem.Status.DOWNLOADING;
            if (target.status == DownloadJob.TrackItem.Status.WAITING) {
                targetJob.tracks.remove(from);
                int firstWaiting = 0;
                while (firstWaiting < targetJob.tracks.size()
                        && (targetJob.tracks.get(firstWaiting).status
                        == DownloadJob.TrackItem.Status.DOWNLOADED
                        || targetJob.tracks.get(firstWaiting).status
                        == DownloadJob.TrackItem.Status.FAILED_TEMPORARY
                        || targetJob.tracks.get(firstWaiting).status
                        == DownloadJob.TrackItem.Status.FAILED_PERMANENT)) {
                    firstWaiting++;
                }
                targetJob.tracks.add(firstWaiting, target);
                targetJob.trackOrderJson = serializeTrackOrderLocked(targetJob);
            }
        }

        DownloadJob running = activeJob;
        if (!alreadyDownloading && running != null) {
            running.yieldInterrupted = true;
            killActiveProcess();
        }
        persistPaused(targetJob);
        persistTrackOrder(targetJob);
        persistQueueOrder();
        publish(true);
        scheduleDispatcher();
    }

    private int indexOfTrackLocked(DownloadJob job, String videoId) {
        for (int i = 0; i < job.tracks.size(); i++) {
            if (videoId.equals(job.tracks.get(i).videoId)) {
                return i;
            }
        }
        return -1;
    }

    private String serializeTrackOrderLocked(DownloadJob job) {
        JSONArray order = new JSONArray();
        for (DownloadJob.TrackItem item : job.tracks) {
            order.put(item.videoId);
        }
        return order.toString();
    }

    private void persistTrackOrder(DownloadJob job) {
        String order = job.trackOrderJson;
        dbExecutor.execute(() -> queueRepository.updateTrackOrder(job, order));
    }

    @Nullable
    private DownloadJob findJobLocked(long jobId) {
        int index = indexOfJobLocked(jobId);
        return index >= 0 ? jobs.get(index) : null;
    }

    private int indexOfJobLocked(long jobId) {
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i).id == jobId) {
                return i;
            }
        }
        return -1;
    }

    private void killActiveProcess() {
        String processId = activeProcessId;
        if (processId != null) {
            try {
                processKiller.destroyProcessById(processId);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "destroyProcessById failed", e);
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private void waitForEngine() throws InterruptedException {
        for (int i = 0; i < ENGINE_WAIT_SECONDS * 2; i++) {
            if (engineReady.getAsBoolean()) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("yt-dlp не смог инициализироваться");
    }

    private void publish(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastPublishMs < 300) {
            return;
        }
        lastPublishMs = now;
        List<DownloadJob> snapshot;
        synchronized (jobs) {
            snapshot = new ArrayList<>(jobs);
        }
        listeners.forEach(listener -> listener.onJobsChanged(snapshot));
    }

    private void cleanupPendingPlaceholders(DownloadJob job) {
        if (!job.isPlaylist || job.playlistName == null) {
            return;
        }
        List<String> pendingVideoIds = new ArrayList<>();
        synchronized (job.tracks) {
            for (DownloadJob.TrackItem item : job.tracks) {
                if (item.status != DownloadJob.TrackItem.Status.DOWNLOADED) {
                    pendingVideoIds.add(item.videoId);
                }
            }
        }
        String playlist = job.playlistName;
        dbExecutor.execute(() -> dao.deletePending(playlist, pendingVideoIds));
    }

    /** Desktop thumbnails are cropped to squares; mqdefault has no letterbox bars. */
    static String thumbnailUrl(String videoId) {
        return "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg";
    }

    /**
     * Видео, которое сменой player-client не оживить: удалено, приватно,
     * заблокировано правообладателем/в регионе. Такие не ретраим — только время терять.
     */
    private static boolean isPermanentlyUnavailable(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.US);
        return lower.contains("removed")
                || lower.contains("deleted")
                || lower.contains("private")
                || lower.contains("terminated")
                || lower.contains("copyright")
                || lower.contains("blocked")
                || lower.contains("who has blocked it")
                || lower.contains("not made this video available");
    }

    private static String shortError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            return e.getClass().getSimpleName();
        }
        // yt-dlp пишет многострочные простыни — берём первую содержательную строку
        for (String line : message.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("ERROR:")) {
                return trimmed;
            }
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
