package com.viby.core;

import com.viby.core.data.Database;
import com.viby.core.data.LibraryRepository;
import com.viby.core.data.RoomPendingDownloadDao;
import com.viby.core.data.AlbumIndex;
import com.viby.core.data.AudioProbe;
import com.viby.core.data.LibraryBackup;
import com.viby.core.data.LibraryFiles;
import com.viby.core.data.LibraryMover;
import com.viby.core.data.LibraryScanner;
import com.viby.core.data.LibraryStorage;
import com.viby.core.data.RoomTrackDao;
import com.viby.core.data.Track;
import com.viby.core.download.AudioDownloader;
import com.viby.core.download.DownloadService;
import com.viby.core.download.DuplicateTracks;
import com.viby.core.download.NetworkMonitor;
import com.viby.core.download.YoutubeExtractor;
import com.viby.core.playback.AudioEngine;
import com.viby.core.playback.EqualizerController;
import com.viby.core.platform.AppDirs;
import com.viby.core.platform.Platform;
import com.viby.core.settings.Settings;
import com.viby.core.sync.GoogleOAuth;
import com.viby.core.sync.YoutubeAccount;
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
    private final DownloadService downloads;
    private final DuplicateTracks duplicates;
    private final LibraryFiles files;
    private final LibraryScanner scanner;
    private final LibraryBackup backup;
    private final LibraryStorage storage;
    private final LibraryMover mover;
    private final AlbumIndex albums;
    private final com.viby.core.playback.LoudnessIndex loudness;
    private final NetworkMonitor network = new NetworkMonitor();
    private final AudioEngine audio;
    private final EqualizerController equalizer;
    private final YoutubeAccount account;
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
        // Room-shaped DAO: every write refreshes the library like Room invalidates LiveData
        RoomTrackDao trackDao = new RoomTrackDao(database, library::notifyChanged);
        this.downloads = new DownloadService(extractor, AudioDownloader.create(ytDlp, extractor, settings::audioQuality),
                ytDlp::destroyProcessById, trackDao,
                RoomPendingDownloadDao.of(database), database.executor, library, network,
                this::isReady, settings::musicDirectory, settings::audioFormat);
        this.duplicates = new DuplicateTracks(trackDao);
        this.files = new LibraryFiles(trackDao, settings::musicDirectory, tools::ffprobe);
        this.scanner = new LibraryScanner(library, settings::musicDirectory, AudioProbe.ffprobe(tools::ffprobe));
        this.backup = new LibraryBackup(library, settings, settings::musicDirectory);
        this.storage = new LibraryStorage(library, settings::musicDirectory);
        this.mover = new LibraryMover(library);
        this.loudness = new com.viby.core.playback.LoudnessIndex(dirs.root().resolve("loudness.json"),
                com.viby.core.playback.LoudnessIndex.ffmpeg(tools::ffmpeg));
        this.albums = new AlbumIndex(dirs.root().resolve("albums.json"), AudioProbe.ffprobe(tools::ffprobe));
        this.audio = new AudioEngine(tools::ffmpeg);
        this.equalizer = new EqualizerController(audio.equalizer(), settings);
        this.account = new YoutubeAccount(new GoogleOAuth(GoogleOAuth.ClientConfig.bundled(),
                dirs.root().resolve("google_account.json")), database, library, downloads);
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
            network.start();
            library.resetInterruptedDownloads();
            setStatus(new Status(State.READY, null, 0, 0, null));
            downloads.restorePending();
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

    public DownloadService downloads() {
        return downloads;
    }

    public LibraryFiles files() {
        return files;
    }

    /** Relinks moved files and adds untracked ones (stage 3: data safety). */
    public LibraryScanner scanner() {
        return scanner;
    }

    public LibraryBackup backup() {
        return backup;
    }

    public LibraryStorage storage() {
        return storage;
    }

    public LibraryMover mover() {
        return mover;
    }

    public AlbumIndex albums() {
        return albums;
    }

    public com.viby.core.playback.LoudnessIndex loudness() {
        return loudness;
    }

    public DuplicateTracks duplicates() {
        return duplicates;
    }

    public YoutubeAccount account() {
        return account;
    }

    public EqualizerController equalizer() {
        return equalizer;
    }

    public AudioEngine audio() {
        return audio;
    }

    /**
     * Downloads the files of tracks again (lost after a reinstall or restored from a backup):
     * they become pending entries that keep their place and are filled in by the downloads.
     *
     * @return how many were queued; tracks without a YouTube link cannot be restored
     */
    public int redownload(List<Track> tracks) {
        List<Track> queued = new java.util.ArrayList<>();
        for (Track track : tracks) {
            if (track.sourceUrl == null && track.videoId != null) {
                track.sourceUrl = "https://www.youtube.com/watch?v=" + track.videoId;
            }
            if (track.sourceUrl == null || track.sourceUrl.isEmpty()) {
                continue;
            }
            track.downloaded = false;
            track.downloadState = Track.DOWNLOAD_WAITING;
            track.downloadProgress = 0;
            queued.add(track);
        }
        library.updateAll(queued);
        queued.forEach(downloads::retryPersistedTrack);
        return queued.size();
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
        network.stop();
        downloads.shutdown();
        database.close();
    }
}
