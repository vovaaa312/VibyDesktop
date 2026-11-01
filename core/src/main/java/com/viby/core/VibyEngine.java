package com.viby.core;

import com.viby.core.data.Database;
import com.viby.core.data.LibraryRepository;
import com.viby.core.data.PendingDownloadDao;
import com.viby.core.download.AudioDownloader;
import com.viby.core.download.DownloadManager;
import com.viby.core.download.YoutubeExtractor;
import com.viby.core.playback.AudioEngine;
import com.viby.core.platform.AppDirs;
import com.viby.core.platform.Platform;
import com.viby.core.settings.Settings;
import com.viby.core.tools.BinaryManager;
import com.viby.core.tools.Tool;
import com.viby.core.ytdlp.VideoInfo;
import com.viby.core.ytdlp.YtDlp;
import com.viby.core.ytdlp.YtDlpRequest;

import org.jspecify.annotations.Nullable;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wires the core together, like VibyApp on Android: the library opens immediately,
 * downloads start once yt-dlp, ffmpeg and deno are installed.
 */
public final class VibyEngine implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(VibyEngine.class.getName());

    public enum State { INITIALIZING, INSTALLING, READY, FAILED }

    /** {@code tool}/bytes are set while INSTALLING; {@code error} when FAILED. */
    public record Status(State state, @Nullable Tool tool, long downloadedBytes, long totalBytes,
                         @Nullable String error) {
    }

    public interface Listener {
        /** Called from a background thread. */
        void onStatus(Status status);
    }

    private final AppDirs dirs;
    private final Settings settings;
    private final BinaryManager tools;
    private final YtDlp ytDlp;
    private final Database database;
    private final LibraryRepository library;
    private final DownloadManager downloads;
    private final AudioEngine audio;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Status status = new Status(State.INITIALIZING, null, 0, 0, null);

    public VibyEngine(AppDirs dirs) throws SQLException {
        this.dirs = dirs;
        this.settings = new Settings(dirs.settingsFile(), dirs.defaultMusicDirectory());
        this.tools = new BinaryManager(dirs.tools(), Platform.current());
        this.ytDlp = new YtDlp(tools);
        this.database = Database.open(dirs.database());
        this.library = new LibraryRepository(database);
        YoutubeExtractor extractor = YoutubeExtractor.create(ytDlp, settings::cookieSource);
        this.downloads = new DownloadManager(extractor, AudioDownloader.create(ytDlp, extractor),
                ytDlp::destroyProcessById, library, new PendingDownloadDao(database),
                settings::musicDirectory, settings::audioFormat);
        this.audio = new AudioEngine(tools::ffmpeg);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onStatus(status);
    }

    /** Installs missing tools, starts the download queue, then auto-updates yt-dlp. */
    public void start() {
        Thread thread = new Thread(this::initialize, "viby-engine");
        thread.setDaemon(true);
        thread.start();
    }

    private void initialize() {
        try {
            if (!tools.missing().isEmpty()) {
                setStatus(new Status(State.INSTALLING, null, 0, -1, null));
                tools.installMissing((tool, done, total) ->
                        setStatus(new Status(State.INSTALLING, tool, done, total, null)));
            }
            downloads.start();
            setStatus(new Status(State.READY, null, 0, 0, null));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "engine init failed", e);
            setStatus(new Status(State.FAILED, null, 0, 0, e.getMessage()));
            return;
        }
        try {
            // YouTube changes often; a daily yt-dlp update fixes most breakages without a release
            tools.updateIfDue(Tool.YT_DLP, Duration.ofDays(1), null);
        } catch (Exception e) {
            LOG.log(Level.INFO, "yt-dlp auto-update failed", e);
        }
    }

    /** Retry after a failed first-launch installation. */
    public void retry() {
        if (status.state() == State.FAILED) {
            start();
        }
    }

    public boolean isReady() {
        return status.state() == State.READY;
    }

    public Status status() {
        return status;
    }

    public AppDirs dirs() {
        return dirs;
    }

    public Settings settings() {
        return settings;
    }

    public BinaryManager tools() {
        return tools;
    }

    public YtDlp ytDlp() {
        return ytDlp;
    }

    public LibraryRepository library() {
        return library;
    }

    public DownloadManager downloads() {
        return downloads;
    }

    public AudioEngine audio() {
        return audio;
    }

    /**
     * Short-lived direct audio URL for a track that is not downloaded yet
     * (port of the Android TrackStreamResolver). Blocking.
     */
    public String resolveStreamUrl(String sourceUrl) throws Exception {
        YtDlpRequest request = new YtDlpRequest(sourceUrl);
        request.addOption("--no-playlist");
        request.addOption("--format", "bestaudio[ext=m4a]/bestaudio/best");
        settings.cookieSource().apply(request);
        VideoInfo info = ytDlp.getInfo(request);
        String url = info.getUrl();
        if (url == null || url.isEmpty()) {
            throw new IllegalStateException("yt-dlp returned no stream URL");
        }
        return url;
    }

    private void setStatus(Status next) {
        status = next;
        listeners.forEach(listener -> listener.onStatus(next));
    }

    @Override
    public void close() throws SQLException {
        audio.close();
        downloads.shutdown();
        database.close();
    }
}
