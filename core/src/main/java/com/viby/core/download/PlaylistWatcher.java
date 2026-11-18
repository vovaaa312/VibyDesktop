package com.viby.core.download;

import com.viby.core.data.LibraryRepository;
import com.viby.core.data.Track;
import com.viby.core.sync.YoutubePlaylistApi;
import com.viby.core.util.YoutubeUrlParser;
import com.viby.core.ytdlp.YtDlpResponse;

import org.jspecify.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Automatic check for new tracks in the playlists downloaded from YouTube.
 *
 * <p>For every playlist it remembers the videos it has already seen there. Only videos that
 * appear after that are downloaded, so a track the user deleted from Viby does not come back.
 * The first check of a playlist only remembers what is there; the "Обновить из YouTube" button
 * still downloads everything that is missing.
 *
 * <p>The YouTube API quota is shared by every user of the built-in key, so it is spent only where
 * it is needed: with an account, one request (one unit per 50 playlists) tells which playlists
 * changed their size, and only those are read. Playlists are read with yt-dlp, which has no quota;
 * the API reads only the playlists yt-dlp cannot open (private ones). Without an account, yt-dlp
 * reads each playlist at most every {@link Policy#ytDlpEveryMs}.
 */
public final class PlaylistWatcher {

    private static final Logger LOG = Logger.getLogger(PlaylistWatcher.class.getName());

    /** A playlist as YouTube lists it now. */
    public record Remote(String title, List<DownloadService.VideoEntry> videos) {
    }

    /** Where playlists are read from. */
    public interface Lister {
        /** yt-dlp: no quota; public, unlisted and (with browser cookies) private playlists. */
        Remote ytDlp(String playlistId) throws Exception;

        /** Whether the YouTube account is signed in (the API methods below work). */
        boolean signedIn();

        /** YouTube API listing, durations unknown (0). */
        Remote api(String playlistId) throws Exception;

        /** Item count of each playlist the account can see. */
        Map<String, Integer> counts(List<String> playlistIds) throws Exception;

        Map<String, Long> durations(List<String> videoIds) throws Exception;
    }

    /** The download queue as the watcher needs it. */
    public interface Queue {
        boolean busy(String url, String playlist);

        void enqueue(String url, String playlist, List<DownloadService.VideoEntry> videos, List<String> only);

        static Queue of(DownloadService downloads) {
            return new Queue() {
                @Override
                public boolean busy(String url, String playlist) {
                    return downloads.hasActiveJob(url, playlist);
                }

                @Override
                public void enqueue(String url, String playlist, List<DownloadService.VideoEntry> videos,
                                    List<String> only) {
                    downloads.enqueueVideos(url, playlist, playlist, videos, only);
                }
            };
        }
    }

    /**
     * How eagerly to read playlists.
     *
     * @param force        read every playlist ("Проверить сейчас")
     * @param ytDlpEveryMs a playlist whose size is unknown is read at most this often
     * @param fullEveryMs  a playlist whose size did not change is still read this often (a video
     *                     added and another removed keep the size)
     */
    public record Policy(boolean force, long ytDlpEveryMs, long fullEveryMs) {
        public static final Policy MANUAL = new Policy(true, 0, 0);
    }

    /** A playlist that got new tracks; they were queued for download. */
    public record Found(String playlist, int count) {
    }

    public record Result(List<Found> found, int checked, int failed, @Nullable String firstError,
                         boolean quotaExceeded) {
        public int newTracks() {
            return found.stream().mapToInt(Found::count).sum();
        }
    }

    private final Path file;
    private final LibraryRepository library;
    private final Queue downloads;
    private final Lister lister;
    private final JSONObject state;

    public PlaylistWatcher(Path file, LibraryRepository library, Queue downloads, Lister lister) {
        this.file = file;
        this.library = library;
        this.downloads = downloads;
        this.lister = lister;
        this.state = load(file);
    }

    /** When the last check ran (epoch ms), 0 if never. */
    public synchronized long lastCheck() {
        return state.optLong("lastCheck", 0L);
    }

    /** Whether the playlist can be checked: it comes from a YouTube playlist that is not a radio mix. */
    public static boolean isWatchable(LibraryRepository.PlaylistSummary playlist) {
        String id = playlist.youtubePlaylistId();
        return id != null && !id.isEmpty() && !YoutubeUrlParser.isRadioPlaylistId(id);
    }

    /**
     * Checks the watchable playlists except {@code excluded}. Blocking; errors of one playlist do
     * not stop the others.
     */
    public synchronized Result check(Set<String> excluded, Policy policy) {
        long now = System.currentTimeMillis();
        List<Found> found = new ArrayList<>();
        int checked = 0;
        int failed = 0;
        String firstError = null;
        boolean quota = false;
        JSONObject playlists = state.optJSONObject("playlists");
        if (playlists == null) {
            playlists = new JSONObject();
            state.put("playlists", playlists);
        }

        List<LibraryRepository.PlaylistSummary> watched = new ArrayList<>();
        for (LibraryRepository.PlaylistSummary playlist : library.playlists()) {
            if (isWatchable(playlist) && !excluded.contains(playlist.name())) {
                watched.add(playlist);
            }
        }
        boolean signedIn = lister.signedIn();
        Map<String, Integer> counts = Map.of();
        if (signedIn && !watched.isEmpty() && !policy.force()) {
            try {
                counts = lister.counts(watched.stream().map(LibraryRepository.PlaylistSummary::youtubePlaylistId)
                        .distinct().toList());
            } catch (Exception e) {
                quota = YoutubePlaylistApi.isQuotaExceeded(e);
                LOG.log(Level.INFO, "playlist sizes failed", e);
            }
        }

        for (LibraryRepository.PlaylistSummary playlist : watched) {
            String id = playlist.youtubePlaylistId();
            String url = playlist.sourceUrl() != null && !playlist.sourceUrl().isEmpty()
                    ? playlist.sourceUrl() : "https://www.youtube.com/playlist?list=" + id;
            JSONObject entry = playlists.optJSONObject(playlist.name());
            boolean first = entry == null || !id.equals(entry.optString("id"));
            Integer count = counts.get(id);
            long since = first ? Long.MAX_VALUE : now - entry.optLong("checkedAt", 0L);
            boolean due;
            if (policy.force() || first) {
                due = true;
            } else if (count != null) {
                due = count != entry.optInt("count", -1) || since >= policy.fullEveryMs();
            } else {
                due = since >= policy.ytDlpEveryMs();
            }
            if (!due || downloads.busy(url, playlist.name())) {
                continue;
            }
            try {
                boolean viaApi = signedIn && entry != null && entry.optBoolean("api");
                Remote remote = null;
                if (!viaApi) {
                    try {
                        remote = lister.ytDlp(id);
                    } catch (Exception e) {
                        if (!signedIn) {
                            throw e;
                        }
                        // private playlist: only the account can read it
                        viaApi = true;
                    }
                }
                if (remote == null) {
                    remote = lister.api(id);
                }
                checked++;

                Set<String> local = new HashSet<>();
                for (Track track : library.tracks(playlist.name())) {
                    if (track.videoId != null) {
                        local.add(track.videoId);
                    }
                }
                Set<String> seen = new LinkedHashSet<>();
                JSONArray known = first ? null : entry.optJSONArray("seen");
                for (int i = 0; known != null && i < known.length(); i++) {
                    seen.add(known.optString(i));
                }
                List<String> fresh = new ArrayList<>();
                if (!first) {
                    for (DownloadService.VideoEntry video : remote.videos()) {
                        if (!seen.contains(video.videoId()) && !local.contains(video.videoId())) {
                            fresh.add(video.videoId());
                        }
                    }
                }
                if (!fresh.isEmpty()) {
                    List<DownloadService.VideoEntry> videos = remote.videos();
                    if (viaApi) {
                        videos = withDurations(videos, fresh);
                    }
                    downloads.enqueue(url, playlist.name(), videos, fresh);
                    found.add(new Found(playlist.name(), fresh.size()));
                }
                for (DownloadService.VideoEntry video : remote.videos()) {
                    seen.add(video.videoId());
                }
                seen.addAll(local);
                playlists.put(playlist.name(), new JSONObject()
                        .put("id", id)
                        .put("checkedAt", now)
                        .put("api", viaApi)
                        .put("count", count != null ? count : -1)
                        .put("seen", new JSONArray(seen)));
            } catch (Exception e) {
                failed++;
                quota |= YoutubePlaylistApi.isQuotaExceeded(e);
                if (firstError == null) {
                    firstError = playlist.name() + ": " + e.getMessage();
                }
                LOG.log(Level.INFO, "auto check failed: " + playlist.name(), e);
            }
        }
        state.put("lastCheck", now);
        save();
        return new Result(found, checked, failed, firstError, quota);
    }

    /** Durations of the new videos only (one quota unit per 50); the rest keep theirs. */
    private List<DownloadService.VideoEntry> withDurations(List<DownloadService.VideoEntry> videos, List<String> fresh) {
        Map<String, Long> durations;
        try {
            durations = lister.durations(fresh);
        } catch (Exception e) {
            return videos; // the download fills the duration in
        }
        List<DownloadService.VideoEntry> result = new ArrayList<>(videos.size());
        for (DownloadService.VideoEntry video : videos) {
            Long duration = durations.get(video.videoId());
            result.add(duration == null ? video
                    : new DownloadService.VideoEntry(video.videoId(), video.title(), video.uploader(), duration));
        }
        return result;
    }

    /** yt-dlp listing (public and unlisted playlists, private ones with browser cookies). */
    public static Remote listWithYtDlp(YoutubeExtractor extractor, String playlistId) throws Exception {
        String url = "https://www.youtube.com/playlist?list=" + playlistId;
        YtDlpResponse response = extractor.getPlaylistInfo(extractor.playlistInfoRequest(url, false),
                "watch-" + playlistId);
        if (response.exitCode() != 0) {
            throw new IOException(response.err().isBlank() ? "yt-dlp: код " + response.exitCode() : response.err().trim());
        }
        return parse(response.out());
    }

    static Remote parse(String json) {
        JSONObject root = new JSONObject(json);
        List<DownloadService.VideoEntry> videos = new ArrayList<>();
        JSONArray entries = root.optJSONArray("entries");
        for (int i = 0; entries != null && i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            String id = entry == null ? "" : entry.optString("id", "");
            if (id.isEmpty()) {
                continue;
            }
            String uploader = entry.optString("uploader", entry.optString("channel", ""));
            videos.add(new DownloadService.VideoEntry(id, entry.optString("title", id),
                    uploader.isEmpty() ? null : uploader, (long) (entry.optDouble("duration", 0) * 1000)));
        }
        return new Remote(root.optString("title", "Playlist"), videos);
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, state.toString(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "cannot save " + file, e);
        }
    }

    private static JSONObject load(Path file) {
        try {
            return Files.isRegularFile(file) ? new JSONObject(Files.readString(file, StandardCharsets.UTF_8)) : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
