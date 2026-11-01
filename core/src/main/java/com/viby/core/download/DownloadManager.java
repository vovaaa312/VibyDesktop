package com.viby.core.download;

import com.viby.core.data.LibraryRepository;
import com.viby.core.data.PendingDownload;
import com.viby.core.data.PendingDownloadDao;
import com.viby.core.data.PlaylistSource;
import com.viby.core.data.Track;
import com.viby.core.util.StorageHelper;
import com.viby.core.util.YoutubeUrlParser;
import com.viby.core.ytdlp.VideoInfo;
import com.viby.core.ytdlp.YtDlpException;

import org.jspecify.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Desktop replacement for the Android DownloadService: a persistent queue of video and
 * playlist jobs processed one at a time on a background thread.
 */
public final class DownloadManager {

    public enum Status { QUEUED, RESOLVING, DOWNLOADING, PAUSED, DONE, DONE_WITH_ERRORS, FAILED }

    public record JobSnapshot(long id, String url, @Nullable String playlistName, boolean isPlaylist,
                              String title, Status status, int totalTracks, int doneTracks,
                              int failedTracks, @Nullable String currentTitle, int currentProgress,
                              long activeTrackId, @Nullable String error) {

        public boolean isFinished() {
            return status == Status.DONE || status == Status.DONE_WITH_ERRORS || status == Status.FAILED;
        }
    }

    public interface Listener {
        /** Called on the download thread; UI code must hop to its own thread. */
        void onJobsChanged(List<JobSnapshot> jobs);
    }

    /** Player clients Android falls back to when the default client yields no usable format. */
    static final String ALT_PLAYER_CLIENTS = "default,tv,mweb";
    public static final String SINGLES_PLAYLIST = "Синглы";
    private static final long PUBLISH_INTERVAL_MS = 250;
    private static final long PERSIST_INTERVAL_MS = 2_000;
    private static final Set<DownloadFailure.Code> FORMAT_FALLBACK_CODES = Set.of(
            DownloadFailure.Code.FORMAT_UNAVAILABLE, DownloadFailure.Code.FFMPEG,
            DownloadFailure.Code.UNKNOWN, DownloadFailure.Code.EXTRACTOR_OUTDATED,
            DownloadFailure.Code.RATE_LIMITED);

    private static final class Job {
        final long id;
        final String url;
        final boolean isPlaylist;
        @Nullable String playlistName;
        volatile boolean paused;
        volatile boolean canceled;
        String title;
        Status status;
        int total;
        int done;
        int failed;
        @Nullable String currentTitle;
        int progress;
        long activeTrackId = -1;
        @Nullable String error;
        final List<Long> trackIds = new ArrayList<>();
        /** Pre-resolved entries (YouTube Data API); null means yt-dlp resolves the URL. */
        @Nullable JSONObject preset;
        int processCounter;

        Job(long id, String url, boolean isPlaylist, @Nullable String playlistName, boolean paused) {
            this.id = id;
            this.url = url;
            this.isPlaylist = isPlaylist;
            this.playlistName = playlistName;
            this.paused = paused;
            this.title = url;
            this.status = paused ? Status.PAUSED : Status.QUEUED;
        }

        JobSnapshot snapshot() {
            return new JobSnapshot(id, url, playlistName, isPlaylist, title, status, total, done,
                    failed, currentTitle, progress, activeTrackId, error);
        }
    }

    /** Pause or cancel requested while the job was running. */
    private static final class JobInterrupted extends Exception {
    }

    record Entry(String videoId, String title, @Nullable String uploader, long durationMs,
                         int position) {
    }

    private final YoutubeExtractor extractor;
    private final AudioDownloader downloader;
    private final ProcessKiller processKiller;
    private final LibraryRepository library;
    private final PendingDownloadDao pending;
    private final Supplier<Path> musicDirectory;
    private final Supplier<String> audioFormat;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Job> jobs = new ArrayList<>();
    private final Object lock = new Object();
    private volatile @Nullable Job active;
    private volatile @Nullable String activeProcessId;
    private volatile boolean running;
    private @Nullable Thread worker;
    private long lastPublish;

    public interface ProcessKiller {
        boolean destroyProcessById(String processId);
    }

    public DownloadManager(YoutubeExtractor extractor, AudioDownloader downloader,
                           ProcessKiller processKiller, LibraryRepository library,
                           PendingDownloadDao pending, Supplier<Path> musicDirectory,
                           Supplier<String> audioFormat) {
        this.extractor = extractor;
        this.downloader = downloader;
        this.processKiller = processKiller;
        this.library = library;
        this.pending = pending;
        this.musicDirectory = musicDirectory;
        this.audioFormat = audioFormat;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** Restores unfinished jobs from the database and starts the worker thread. */
    public void start() throws SQLException {
        library.resetInterruptedDownloads();
        synchronized (lock) {
            for (PendingDownload row : pending.all()) {
                Job job = new Job(row.id, row.url, row.isPlaylist, row.playlistName, row.paused);
                job.preset = row.trackOrderJson != null ? new JSONObject(row.trackOrderJson) : null;
                jobs.add(job);
            }
            running = true;
        }
        Thread thread = new Thread(this::workerLoop, "viby-downloads");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        publish(true);
    }

    public void shutdown() {
        running = false;
        String processId = activeProcessId;
        if (processId != null) {
            processKiller.destroyProcessById(processId);
        }
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
            synchronized (lock) {
                lock.notifyAll();
            }
            try {
                // the worker persists the interrupted track; wait so the database outlives it
                thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Queues a video, playlist or YouTube Mix URL.
     *
     * @param playlistName target playlist; null uses the YouTube playlist title
     *                     (or {@link #SINGLES_PLAYLIST} for single videos)
     */
    public long enqueue(String url, @Nullable String playlistName) throws SQLException {
        String clean = url.trim();
        boolean isPlaylist = isPlaylistUrl(clean);
        if (!isPlaylist && YoutubeUrlParser.videoId(clean) == null) {
            throw new IllegalArgumentException("Not a YouTube video or playlist URL: " + clean);
        }
        String target = playlistName != null && !playlistName.isBlank() ? playlistName.trim()
                : isPlaylist ? null : SINGLES_PLAYLIST;
        PendingDownload row = new PendingDownload();
        row.url = clean;
        row.playlistName = target;
        row.isPlaylist = isPlaylist;
        row.createdAt = System.currentTimeMillis();
        row.queuePosition = row.createdAt;
        long id = pending.insert(row);
        if (target != null) {
            library.ensurePlaylist(target);
        }
        synchronized (lock) {
            jobs.add(new Job(id, clean, isPlaylist, target, false));
            lock.notifyAll();
        }
        publish(true);
        return id;
    }

    /**
     * Any URL with a {@code list} parameter is a playlist: browsers show
     * {@code watch?v=…&list=…} while a playlist is playing. The UI strips the list for
     * "only this video".
     */
    static boolean isPlaylistUrl(String url) {
        return YoutubeUrlParser.playlistId(url) != null;
    }

    /** One video of a pre-resolved list; {@code durationMs} 0 when unknown. */
    public record VideoEntry(String videoId, String title, @Nullable String uploader, long durationMs) {
    }

    /**
     * Queues videos already listed through the YouTube Data API, e.g. a private playlist of the
     * signed-in account that yt-dlp cannot open without browser cookies.
     */
    public long enqueueVideos(String sourceUrl, String playlistName, String title, List<VideoEntry> videos)
            throws SQLException {
        JSONArray entries = new JSONArray();
        for (VideoEntry video : videos) {
            entries.put(new JSONObject().put("id", video.videoId()).put("title", video.title())
                    .put("channel", video.uploader()).put("duration", video.durationMs() / 1000.0));
        }
        JSONObject preset = new JSONObject().put("_type", "playlist").put("title", title).put("entries", entries);
        PendingDownload row = new PendingDownload();
        row.url = sourceUrl;
        row.playlistName = playlistName;
        row.isPlaylist = true;
        row.createdAt = System.currentTimeMillis();
        row.queuePosition = row.createdAt;
        row.trackOrderJson = preset.toString();
        long id = pending.insert(row);
        library.ensurePlaylist(playlistName);
        Job job = new Job(id, sourceUrl, true, playlistName, false);
        job.preset = preset;
        synchronized (lock) {
            jobs.add(job);
            lock.notifyAll();
        }
        publish(true);
        return id;
    }

    public void pause(long id) {
        Job job = find(id);
        if (job == null || job.status == Status.PAUSED || isFinished(job)) {
            return;
        }
        job.paused = true;
        persistPaused(job);
        if (active == job) {
            interruptActive();
        } else {
            job.status = Status.PAUSED;
        }
        publish(true);
    }

    public void resume(long id) {
        Job job = find(id);
        if (job == null || !job.paused) {
            return;
        }
        job.paused = false;
        job.status = Status.QUEUED;
        persistPaused(job);
        synchronized (lock) {
            lock.notifyAll();
        }
        publish(true);
    }

    public void cancel(long id) {
        Job job = find(id);
        if (job == null) {
            return;
        }
        job.canceled = true;
        if (active == job) {
            interruptActive();
            return;
        }
        removeCanceled(job);
    }

    public void clearFinished() {
        synchronized (lock) {
            jobs.removeIf(DownloadManager::isFinished);
        }
        publish(true);
    }

    public List<JobSnapshot> jobs() {
        synchronized (lock) {
            return jobs.stream().map(Job::snapshot).toList();
        }
    }

    // ---- worker ----

    private void workerLoop() {
        while (running) {
            Job job;
            synchronized (lock) {
                job = jobs.stream()
                        .filter(candidate -> candidate.status == Status.QUEUED && !candidate.canceled)
                        .findFirst().orElse(null);
                if (job == null) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                active = job;
            }
            runJob(job);
        }
    }

    private void runJob(Job job) {
        job.error = null;
        job.failed = 0;
        try {
            List<Entry> entries = resolve(job);
            String playlist = job.playlistName;
            List<Track> queue = preparePlaceholders(job, entries, playlist);
            job.status = Status.DOWNLOADING;
            publish(true);
            for (Track item : queue) {
                checkInterrupted(job);
                job.currentTitle = item.title;
                job.progress = 0;
                job.activeTrackId = item.id;
                publish(true);
                try {
                    downloadWithRetries(job, item, playlist);
                    job.done++;
                } catch (JobInterrupted e) {
                    throw e;
                } catch (Exception e) {
                    job.failed++;
                }
            }
            job.status = job.failed > 0 ? Status.DONE_WITH_ERRORS : Status.DONE;
            job.currentTitle = null;
            job.activeTrackId = -1;
            deletePending(job);
        } catch (JobInterrupted e) {
            onInterrupted(job);
        } catch (Exception e) {
            if (job.canceled || job.paused) {
                onInterrupted(job);
            } else {
                job.status = Status.FAILED;
                job.error = userMessage(DownloadFailure.classify(e));
                job.activeTrackId = -1;
                deletePending(job);
            }
        } finally {
            active = null;
            activeProcessId = null;
            publish(true);
        }
    }

    private void onInterrupted(Job job) {
        job.activeTrackId = -1;
        if (job.canceled) {
            removeCanceled(job);
        } else {
            job.status = Status.PAUSED;
        }
    }

    private List<Entry> resolve(Job job) throws Exception {
        job.status = Status.RESOLVING;
        job.currentTitle = null;
        publish(true);
        List<Entry> entries = new ArrayList<>();
        if (job.isPlaylist) {
            JSONObject root;
            if (job.preset != null) {
                root = job.preset;
            } else {
                String processId = nextProcessId(job);
                activeProcessId = processId;
                String out = extractor.getPlaylistInfo(
                        extractor.playlistInfoRequest(job.url, YoutubeUrlParser.isRadioMixUrl(job.url)),
                        processId).out();
                checkInterrupted(job);
                root = new JSONObject(out);
            }
            job.title = root.optString("title", "Playlist");
            JSONArray items = root.optJSONArray("entries");
            if (!"playlist".equals(root.optString("_type"))) {
                // yt-dlp falls back to the single video when YouTube offers no playlist/Mix
                if (YoutubeUrlParser.isRadioMixUrl(job.url)) {
                    throw new IllegalStateException("YouTube не собрал микс для этого трека");
                }
                items = new JSONArray().put(root);
            }
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    Entry entry = entry(items.optJSONObject(i), i);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            }
            if (job.playlistName == null) {
                job.playlistName = StorageHelper.sanitize(job.title);
                pending.setPlaylistName(job.id, job.playlistName);
            }
            library.ensurePlaylist(job.playlistName);
            PlaylistSource source = new PlaylistSource();
            source.playlistName = job.playlistName;
            source.sourceUrl = job.url;
            source.youtubePlaylistId = YoutubeUrlParser.playlistId(job.url);
            source.updatedAt = System.currentTimeMillis();
            library.saveSource(source);
        } else {
            VideoInfo info = extractor.getVideoInfo(extractor.videoInfoRequest(job.url));
            checkInterrupted(job);
            String videoId = info.getId() != null ? info.getId() : YoutubeUrlParser.videoId(job.url);
            job.title = info.getTitle() != null ? info.getTitle() : job.url;
            entries.add(new Entry(videoId, job.title, info.getArtist(), info.getDuration() * 1000, 0));
            library.ensurePlaylist(job.playlistName);
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("Плейлист пуст или недоступен");
        }
        job.total = entries.size();
        return entries;
    }

    static @Nullable Entry entry(@Nullable JSONObject json, int position) {
        if (json == null) {
            return null;
        }
        String videoId = json.optString("id", "");
        String title = json.optString("title", "");
        // flat playlists list unavailable items as "[Private video]" / "[Deleted video]"
        if (videoId.isEmpty() || title.isEmpty() || (title.startsWith("[") && title.endsWith(" video]"))) {
            return null;
        }
        String uploader = json.isNull("channel") ? json.optString("uploader", null)
                : json.optString("channel", null);
        return new Entry(videoId, title, cleanArtist(uploader),
                Math.round(json.optDouble("duration", 0) * 1000), position);
    }

    /** YouTube Music auto-generated channels are named "Artist - Topic". */
    static @Nullable String cleanArtist(@Nullable String uploader) {
        if (uploader == null || uploader.isBlank()) {
            return null;
        }
        String value = uploader.trim();
        return value.endsWith(" - Topic") ? value.substring(0, value.length() - 8) : value;
    }

    private List<Track> preparePlaceholders(Job job, List<Entry> entries, String playlist) {
        List<Track> queue = new ArrayList<>();
        job.done = 0;
        for (Entry entry : entries) {
            Track existing = library.findInPlaylist(playlist, entry.videoId());
            if (existing != null) {
                job.trackIds.add(existing.id);
                if (existing.downloaded) {
                    job.done++;
                } else {
                    queue.add(existing);
                }
                continue;
            }
            Track track = new Track();
            track.videoId = entry.videoId();
            track.title = entry.title();
            track.uploader = entry.uploader();
            track.durationMs = entry.durationMs();
            track.playlistName = playlist;
            track.thumbnailUrl = thumbnailUrl(entry.videoId());
            track.youtubePosition = job.isPlaylist ? entry.position() : null;
            track.sourceUrl = watchUrl(entry.videoId());
            track.createdAt = System.currentTimeMillis();
            Track reusable = library.findReusableDownload(entry.videoId());
            if (reusable != null) {
                // one file shared across playlists, like Android's LibraryImporter
                track.filePath = reusable.filePath;
                track.downloadFormat = reusable.downloadFormat;
                track.downloaded = true;
                track.downloadState = Track.DOWNLOAD_DOWNLOADED;
                track.downloadProgress = 100;
                library.insert(track);
                job.trackIds.add(track.id);
                job.done++;
                continue;
            }
            track.downloaded = false;
            track.downloadState = Track.DOWNLOAD_WAITING;
            library.insert(track);
            job.trackIds.add(track.id);
            queue.add(track);
        }
        return queue;
    }

    private void downloadWithRetries(Job job, Track item, String playlist) throws Exception {
        File directory = StorageHelper.playlistDir(musicDirectory.get(), playlist);
        String baseName = baseName(directory, item);
        int attempt = 0;
        while (true) {
            attempt++;
            checkInterrupted(job);
            item.downloadAttemptCount++;
            item.downloadLastAttemptAt = System.currentTimeMillis();
            item.downloadState = Track.DOWNLOAD_DOWNLOADING;
            item.downloadErrorCode = null;
            item.downloadErrorMessage = null;
            library.update(item);
            try {
                File file = downloadWithFormatFallback(job, item, directory, baseName);
                item.filePath = file.getAbsolutePath();
                item.downloaded = true;
                item.downloadState = Track.DOWNLOAD_DOWNLOADED;
                item.downloadProgress = 100;
                library.update(item);
                return;
            } catch (YtDlpException.Canceled e) {
                throw interrupted(job, item);
            } catch (JobInterrupted e) {
                throw interrupted(job, item);
            } catch (Exception e) {
                if (job.paused || job.canceled) {
                    throw interrupted(job, item);
                }
                DownloadFailure failure = DownloadFailure.classify(e);
                if (DownloadRetryPolicy.shouldRetry(failure, attempt)) {
                    item.downloadState = Track.DOWNLOAD_RETRY_SCHEDULED;
                    library.update(item);
                    sleepInterruptibly(job, DownloadRetryPolicy.delayAfterAttempt(attempt));
                    continue;
                }
                item.downloadState = failure.retryable
                        ? Track.DOWNLOAD_FAILED_TEMPORARY : Track.DOWNLOAD_FAILED_PERMANENT;
                item.downloadErrorCode = failure.code.name();
                item.downloadErrorMessage = userMessage(failure);
                item.downloadRequiresAuthorization = failure.requiresAuthorization;
                library.update(item);
                throw e;
            }
        }
    }

    /** Android order: preferred format, then alternate player clients, then m4a. */
    private File downloadWithFormatFallback(Job job, Track item, File directory, String baseName)
            throws Exception {
        String preferred = audioFormat.get();
        try {
            return downloadOnce(job, item, directory, baseName, preferred, null);
        } catch (YtDlpException.Canceled e) {
            throw e;
        } catch (Exception first) {
            if (!FORMAT_FALLBACK_CODES.contains(DownloadFailure.classify(first).code) || job.paused
                    || job.canceled) {
                throw first;
            }
            try {
                return downloadOnce(job, item, directory, baseName, preferred, ALT_PLAYER_CLIENTS);
            } catch (YtDlpException.Canceled e) {
                throw e;
            } catch (Exception second) {
                if (!"mp3".equals(preferred) || job.paused || job.canceled) {
                    throw second;
                }
                return downloadOnce(job, item, directory, baseName, "m4a", ALT_PLAYER_CLIENTS);
            }
        }
    }

    private File downloadOnce(Job job, Track item, File directory, String baseName, String format,
                              @Nullable String playerClients) throws Exception {
        item.downloadFormat = format;
        String processId = nextProcessId(job);
        activeProcessId = processId;
        long[] lastPersist = {0};
        job.progress = 0;
        return downloader.download(watchUrl(item.videoId), directory, baseName, format, playerClients,
                processId, (progress, eta, line) -> {
                    int resolved = DownloadProgressParser.resolve(job.progress, progress, line);
                    if (resolved == job.progress) {
                        return;
                    }
                    job.progress = resolved;
                    item.downloadProgress = resolved;
                    long now = System.currentTimeMillis();
                    if (now - lastPersist[0] >= PERSIST_INTERVAL_MS) {
                        lastPersist[0] = now;
                        library.updateProgress(item.id, Track.DOWNLOAD_DOWNLOADING, resolved);
                    }
                    publish(false);
                });
    }

    /** "Title.mp3", or "Title [videoId].mp3" when another file already uses the name. */
    static String baseName(File directory, Track item) {
        String baseName = StorageHelper.sanitize(item.title);
        if (new File(directory, baseName + ".mp3").exists()
                || new File(directory, baseName + ".m4a").exists()) {
            baseName = baseName + " [" + item.videoId + "]";
        }
        return baseName;
    }

    private JobInterrupted interrupted(Job job, Track item) {
        item.downloadState = Track.DOWNLOAD_WAITING;
        item.downloadProgress = 0;
        library.update(item);
        return new JobInterrupted();
    }

    private void checkInterrupted(Job job) throws JobInterrupted {
        if (job.paused || job.canceled || !running) {
            throw new JobInterrupted();
        }
    }

    private void sleepInterruptibly(Job job, long delayMs) throws JobInterrupted {
        long until = System.currentTimeMillis() + delayMs;
        synchronized (lock) {
            long left;
            while ((left = until - System.currentTimeMillis()) > 0 && !job.paused && !job.canceled) {
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new JobInterrupted();
                }
            }
        }
        checkInterrupted(job);
    }

    private void interruptActive() {
        String processId = activeProcessId;
        if (processId != null) {
            processKiller.destroyProcessById(processId);
        }
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void removeCanceled(Job job) {
        deletePending(job);
        for (long trackId : job.trackIds) {
            Track track = library.track(trackId);
            if (track != null && !track.downloaded) {
                library.delete(trackId);
            }
        }
        synchronized (lock) {
            jobs.remove(job);
        }
        publish(true);
    }

    private void deletePending(Job job) {
        try {
            pending.delete(job.id);
        } catch (SQLException ignored) {
            // the row is re-read on next start; a stale row only re-resolves the job
        }
    }

    private void persistPaused(Job job) {
        try {
            pending.setPaused(job.id, job.paused);
        } catch (SQLException ignored) {
            // pause state is still applied in memory
        }
    }

    private @Nullable Job find(long id) {
        synchronized (lock) {
            return jobs.stream().filter(job -> job.id == id).findFirst().orElse(null);
        }
    }

    private static boolean isFinished(Job job) {
        return job.status == Status.DONE || job.status == Status.DONE_WITH_ERRORS
                || job.status == Status.FAILED;
    }

    private String nextProcessId(Job job) {
        return "job-" + job.id + "-" + (++job.processCounter);
    }

    private void publish(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastPublish < PUBLISH_INTERVAL_MS) {
            return;
        }
        lastPublish = now;
        List<JobSnapshot> snapshot = jobs();
        listeners.forEach(listener -> listener.onJobsChanged(snapshot));
    }

    static String watchUrl(String videoId) {
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    /** 320x180 without letterboxing; the UI crops it to a square. */
    static String thumbnailUrl(String videoId) {
        return "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg";
    }

    static String userMessage(DownloadFailure failure) {
        return switch (failure.code) {
            case VIDEO_REMOVED -> "Видео удалено";
            case VIDEO_UNAVAILABLE -> "Видео недоступно";
            case VIDEO_PRIVATE -> "Приватное видео — нужен вход в аккаунт";
            case REGION_BLOCKED -> "Недоступно в вашем регионе";
            case AGE_RESTRICTED -> "Возрастное ограничение — нужен вход в аккаунт";
            case EXTRACTOR_OUTDATED -> "yt-dlp устарел — обновите его в настройках";
            case AUTH_REQUIRED -> "Нужен вход в аккаунт YouTube";
            case RATE_LIMITED -> "YouTube ограничил запросы — попробуйте позже или войдите в аккаунт";
            case NETWORK -> "Нет соединения с YouTube";
            case FFMPEG -> "Ошибка конвертации FFmpeg";
            case STORAGE_FULL -> "Недостаточно места на диске";
            case FILE_SYSTEM -> "Не удалось записать файл";
            case FORMAT_UNAVAILABLE -> "Нет доступного аудиоформата";
            case UNKNOWN -> "Неизвестная ошибка загрузки";
        };
    }
}
