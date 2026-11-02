package com.viby.core.sync;

import com.viby.core.data.Database;
import com.viby.core.data.LibraryRepository;
import com.viby.core.data.PlaylistSource;
import com.viby.core.data.Track;
import com.viby.core.download.DownloadManager;

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Signed-in YouTube account features on top of the Data API. All methods block. */
public final class YoutubeAccount {

    public interface Progress {
        void onProgress(int done, int total);
    }

    private final GoogleOAuth oauth;
    private final YoutubePlaylistApi api = new YoutubePlaylistApi();
    private final LibraryRepository library;
    private final DownloadManager downloads;
    private final YoutubePlaylistSync sync;
    private volatile YoutubePlaylistApi.@Nullable Channel channel;

    public YoutubeAccount(GoogleOAuth oauth, Database database, LibraryRepository library,
                          DownloadManager downloads) {
        this.oauth = oauth;
        this.library = library;
        this.downloads = downloads;
        this.sync = new YoutubePlaylistSync(database);
    }

    /**
     * Links local tracks to the owned YouTube playlist, turns on two-way sync and sends tracks
     * that were added locally (e.g. "send to playlist") to YouTube at their local positions.
     */
    public YoutubePlaylistSync.EnableResult enableSync(String playlistName) throws Exception {
        List<String> additions = localAdditions(playlistName);
        try {
            YoutubePlaylistSync.EnableResult result = sync.enable(playlistName, oauth.accessToken());
            if (!additions.isEmpty()) {
                sync.syncMissingTracks(playlistName, additions, oauth.accessToken());
            }
            return result;
        } finally {
            library.notifyChanged();
        }
    }

    /**
     * Video ids added locally and never seen on YouTube. Tracks that came from the YouTube
     * playlist and were later removed there are not included, so they are not re-added.
     */
    public List<String> localAdditions(String playlistName) {
        return library.tracks(playlistName).stream()
                .filter(track -> track.videoId != null && !track.videoId.isEmpty()
                        && track.youtubePlaylistItemId == null && track.youtubePosition == null)
                .map(track -> track.videoId)
                .toList();
    }

    public void disableSync(String playlistName) {
        sync.disable(playlistName);
        library.notifyChanged();
    }

    /**
     * Sends the given videos (default: {@link #localAdditions}) to the synced YouTube playlist.
     *
     * @return null when there was nothing to send
     */
    public YoutubePlaylistSync.@Nullable MissingTracksResult pushMissing(String playlistName,
                                                                          @Nullable List<String> videoIds)
            throws Exception {
        List<String> requested = videoIds != null ? videoIds : localAdditions(playlistName);
        if (requested.isEmpty()) {
            return null;
        }
        try {
            return sync.syncMissingTracks(playlistName, requested, oauth.accessToken());
        } finally {
            library.notifyChanged();
        }
    }

    /** Removes tracks locally and from the synced YouTube playlist. */
    public YoutubePlaylistSync.DeleteResult deleteSynced(String playlistName, List<Long> trackIds)
            throws Exception {
        List<Track> selected = library.tracks(playlistName).stream()
                .filter(track -> trackIds.contains(track.id)).toList();
        try {
            return sync.deleteTracks(playlistName, selected, true, oauth.accessToken());
        } finally {
            library.notifyChanged();
        }
    }

    public boolean isConfigured() {
        return oauth.isConfigured();
    }

    public boolean isSignedIn() {
        return oauth.isSignedIn();
    }

    public YoutubePlaylistApi.Channel signIn(Consumer<URI> openBrowser) throws Exception {
        oauth.signIn(openBrowser, Duration.ofMinutes(5));
        return channel();
    }

    public void signOut() {
        channel = null;
        oauth.signOut();
    }

    public YoutubePlaylistApi.Channel channel() throws Exception {
        YoutubePlaylistApi.Channel cached = channel;
        if (cached == null) {
            cached = api.myChannel(oauth.accessToken());
            channel = cached;
        }
        return cached;
    }

    public List<YoutubePlaylistApi.AccountPlaylist> playlists() throws Exception {
        return api.myPlaylists(oauth.accessToken());
    }

    /**
     * Lists the playlist through the API (works for private playlists without browser
     * cookies) and queues its videos into {@code playlistName}.
     */
    public long download(YoutubePlaylistApi.AccountPlaylist playlist, String playlistName) throws Exception {
        String token = oauth.accessToken();
        List<YoutubePlaylistApi.PlaylistVideo> videos = api.playlistVideos(token, playlist.id());
        if (videos.isEmpty()) {
            throw new IllegalStateException("В плейлисте нет доступных видео");
        }
        Map<String, Long> durations = api.durations(token,
                videos.stream().map(YoutubePlaylistApi.PlaylistVideo::videoId).toList());
        List<DownloadManager.VideoEntry> entries = new ArrayList<>();
        for (YoutubePlaylistApi.PlaylistVideo video : videos) {
            entries.add(new DownloadManager.VideoEntry(video.videoId(), video.title(), video.channel(),
                    durations.getOrDefault(video.videoId(), 0L)));
        }
        return downloads.enqueueVideos("https://www.youtube.com/playlist?list=" + playlist.id(),
                playlistName, playlist.title(), entries);
    }

    /**
     * Creates a private YouTube playlist with the YouTube tracks of a local playlist
     * (Android saves radio snapshots the same way). Returns the playlist URL.
     */
    public String saveToYoutube(String playlistName, @Nullable Progress progress) throws Exception {
        Set<String> videoIds = new LinkedHashSet<>();
        for (Track track : library.tracks(playlistName)) {
            if (track.videoId != null && !track.videoId.isEmpty()) {
                videoIds.add(track.videoId);
            }
        }
        if (videoIds.isEmpty()) {
            throw new IllegalStateException("В плейлисте нет треков с YouTube");
        }
        String token = oauth.accessToken();
        YoutubePlaylistApi.OwnedPlaylist created = api.createPrivatePlaylist(token, playlistName,
                "Сохранено из Viby");
        int done = 0;
        for (String videoId : videoIds) {
            try {
                api.insertAtPosition(oauth.accessToken(), created.id, videoId, done);
            } catch (YoutubePlaylistApi.ApiException e) {
                // a removed or blocked video must not abort the whole playlist
                if (e.statusCode != 404 && e.statusCode != 403) {
                    throw e;
                }
            }
            done++;
            if (progress != null) {
                progress.onProgress(done, videoIds.size());
            }
        }
        String url = "https://www.youtube.com/playlist?list=" + created.id;
        PlaylistSource source = new PlaylistSource();
        source.playlistName = playlistName;
        source.sourceUrl = url;
        source.youtubePlaylistId = created.id;
        source.youtubeOwnerChannelId = created.channelId;
        source.updatedAt = System.currentTimeMillis();
        library.saveSource(source);
        // link the new remote items so later local edits sync both ways
        enableSync(playlistName);
        return url;
    }
}
