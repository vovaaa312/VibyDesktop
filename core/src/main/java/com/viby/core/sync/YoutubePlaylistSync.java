package com.viby.core.sync;


import org.jspecify.annotations.Nullable;

import com.viby.core.data.PlaylistSource;
import com.viby.core.data.Track;
import com.viby.core.data.Database;
import com.viby.core.data.YoutubePlaylistOrder;
import com.viby.core.util.YoutubeUrlParser;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Blocking synchronization operations. Call only from a background executor. */
public final class YoutubePlaylistSync {

    private final SyncStore store;
    private final YoutubePlaylistApi api;

    public static final class EnableResult {
        public final String remoteTitle;
        public final int linkedTracks;

        EnableResult(String remoteTitle, int linkedTracks) {
            this.remoteTitle = remoteTitle;
            this.linkedTracks = linkedTracks;
        }
    }

    public static final class AddResult {
        public final boolean downloadRequired;

        AddResult(boolean downloadRequired) {
            this.downloadRequired = downloadRequired;
        }
    }

    public static final class DeleteResult {
        public final List<Track> removed;
        public final int failedCount;
        @Nullable public final String firstError;

        DeleteResult(List<Track> removed, int failedCount, @Nullable String firstError) {
            this.removed = removed;
            this.failedCount = failedCount;
            this.firstError = firstError;
        }
    }

    public static final class SaveRadioResult {
        public final String remoteTitle;
        public final int addedCount;
        public final int failedCount;
        @Nullable public final String firstError;

        SaveRadioResult(String remoteTitle, int addedCount, int failedCount,
                        @Nullable String firstError) {
            this.remoteTitle = remoteTitle;
            this.addedCount = addedCount;
            this.failedCount = failedCount;
            this.firstError = firstError;
        }
    }

    public static final class MissingTracksResult {
        public final int addedCount;
        public final int failedCount;
        @Nullable public final String firstError;

        MissingTracksResult(int addedCount, int failedCount,
                            @Nullable String firstError) {
            this.addedCount = addedCount;
            this.failedCount = failedCount;
            this.firstError = firstError;
        }
    }

    public YoutubePlaylistSync(Database database) {
        this(database, new YoutubePlaylistApi());
    }

    YoutubePlaylistSync(Database database, YoutubePlaylistApi api) {
        this.store = new SyncStore(database);
        this.api = api;
    }

    public EnableResult enable(String localPlaylist, String accessToken) throws Exception {
        PlaylistSource source = requireSource(localPlaylist);
        String playlistId = playlistId(source);
        YoutubePlaylistApi.OwnedPlaylist owned =
                api.findOwnedPlaylist(accessToken, playlistId);
        if (owned == null) {
            throw new IllegalStateException(
                    "Этот плейлист не принадлежит выбранному Google-аккаунту");
        }
        List<YoutubePlaylistApi.PlaylistItem> remote =
                api.listItems(accessToken, playlistId);
        final int[] linked = {0};
        store.runInTransaction(() -> {
            List<Track> tracks = store.getPlaylistSync(localPlaylist);
            // desktop: keep the local order; tracks sent to the top must not drop to the bottom
            linked[0] = linkTracks(tracks, remote, false);
            if (!tracks.isEmpty()) {
                store.updateAll(tracks);
            }
            source.youtubePlaylistId = playlistId;
            source.youtubeOwnerChannelId = owned.channelId;
            source.youtubeSyncEnabled = true;
            source.updatedAt = System.currentTimeMillis();
            store.upsert(source);
        });
        return new EnableResult(owned.title, linked[0]);
    }

    void disable(String localPlaylist) {
        PlaylistSource source = store.getSync(localPlaylist);
        if (source == null) {
            return;
        }
        source.youtubeSyncEnabled = false;
        store.upsert(source);
    }

    /** Saves the current finite radio snapshot as a private owned YouTube playlist. */
    public SaveRadioResult saveRadioToYoutube(String localPlaylist, String accessToken)
            throws Exception {
        PlaylistSource source = requireSource(localPlaylist);
        if (!YoutubeUrlParser.isRadioMixUrl(source.sourceUrl)) {
            throw new IllegalStateException(
                    "Не удалось определить ID оригинального плейлиста YouTube");
        }
        SyncStore dao = store;
        List<Track> tracks = dao.getPlaylistSync(localPlaylist);
        if (tracks.isEmpty()) {
            throw new IllegalStateException("Дождитесь загрузки списка треков радио");
        }

        String title = truncate(localPlaylist, 150);
        YoutubePlaylistApi.OwnedPlaylist created = api.createPrivatePlaylist(
                accessToken, title, "Создано из радио по треку в Viby.");

        int added = 0;
        int failed = 0;
        String firstError = null;
        for (Track track : tracks) {
            track.youtubePlaylistItemId = null;
            track.youtubePosition = null;
            if (track.videoId == null || track.videoId.isEmpty()) {
                failed++;
                if (firstError == null) {
                    firstError = "У трека нет YouTube ID, поэтому радио недоступно";
                }
                continue;
            }
            try {
                YoutubePlaylistApi.PlaylistItem item = api.insertAtPosition(
                        accessToken, created.id, track.videoId, added);
                track.youtubePlaylistItemId = item.id;
                track.youtubePosition = item.position;
                added++;
            } catch (Exception error) {
                failed++;
                if (firstError == null) {
                    firstError = error.getMessage();
                }
            }
        }

        source.youtubePlaylistId = created.id;
        source.youtubeOwnerChannelId = created.channelId;
        source.youtubeSyncEnabled = true;
        source.updatedAt = System.currentTimeMillis();
        store.runInTransaction(() -> {
            dao.updateAll(tracks);
            store.upsert(source);
        });
        return new SaveRadioResult(created.title, added, failed, firstError);
    }

    public AddResult addTrackAtStart(String localPlaylist, String url,
                                     String accessToken) throws Exception {
        String videoId = YoutubeUrlParser.videoId(url);
        if (videoId == null) {
            throw new IllegalArgumentException(
                    "Это не похоже на ссылку YouTube");
        }
        PlaylistSource source = requireEnabledSource(localPlaylist);
        String playlistId = playlistId(source);
        SyncStore dao = store;
        Track downloaded = dao.getDownloadedByVideoIdSync(localPlaylist, videoId);
        Track any = dao.getByVideoIdSync(localPlaylist, videoId);
        if (downloaded == null && any != null) {
            throw new IllegalStateException(
                    "Дождитесь окончания загрузки уже добавленной копии этого трека");
        }

        YoutubePlaylistApi.PlaylistItem remote =
                api.insertAtStart(accessToken, playlistId, videoId);
        try {
            store.runInTransaction(() -> {
                Track added = downloaded != null
                        ? duplicate(downloaded) : placeholder(localPlaylist, videoId, url);
                added.youtubePlaylistItemId = remote.id;
                added.youtubePosition = remote.position;
                added.position = dao.nextPosition(localPlaylist);
                added.id = dao.insert(added);

                List<Track> tracks = dao.getPlaylistSync(localPlaylist);
                for (Track track : tracks) {
                    if (track.id != added.id && track.youtubePosition != null
                            && track.youtubePosition >= remote.position) {
                        track.youtubePosition++;
                    }
                }
                YoutubePlaylistOrder.sort(tracks);
                for (int i = 0; i < tracks.size(); i++) {
                    tracks.get(i).position = i;
                }
                dao.updateAll(tracks);
            });
        } catch (RuntimeException databaseFailure) {
            try {
                api.deleteItem(accessToken, remote.id);
            } catch (Exception ignored) {
            }
            throw databaseFailure;
        }
        return new AddResult(downloaded == null);
    }

    /** Adds every local YouTube-backed occurrence which is absent from the remote playlist. */
    public MissingTracksResult syncMissingTracks(String localPlaylist,
                                                  String accessToken)
            throws Exception {
        return syncMissingTracks(localPlaylist, null, accessToken);
    }

    /** Adds only requested video occurrences; null means all missing YouTube-backed tracks. */
    public MissingTracksResult syncMissingTracks(String localPlaylist,
                                                  @Nullable List<String> requestedVideoIds,
                                                  String accessToken)
            throws Exception {
        PlaylistSource source = requireEnabledSource(localPlaylist);
        String playlistId = playlistId(source);
        YoutubePlaylistApi.OwnedPlaylist owned =
                api.findOwnedPlaylist(accessToken, playlistId);
        if (owned == null) {
            throw new IllegalStateException(
                    "Этот плейлист не принадлежит выбранному Google-аккаунту");
        }

        List<YoutubePlaylistApi.PlaylistItem> remote =
                api.listItems(accessToken, playlistId);
        SyncStore dao = store;
        List<Track> tracks = dao.getPlaylistSync(localPlaylist);
        linkTracks(tracks, remote, false);
        List<Track> missing = missingYoutubeTracks(tracks, requestedVideoIds);

        int added = 0;
        int failed = 0;
        String firstError = null;
        List<YoutubePlaylistApi.PlaylistItem> inserted = new ArrayList<>();
        for (Track track : missing) {
            try {
                int position = Math.max(0,
                        Math.min(track.position, remote.size() + added));
                YoutubePlaylistApi.PlaylistItem item = api.insertAtPosition(
                        accessToken, playlistId, track.videoId, position);
                track.youtubePlaylistItemId = item.id;
                track.youtubePosition = item.position;
                inserted.add(item);
                added++;
            } catch (Exception error) {
                failed++;
                if (firstError == null) {
                    firstError = error.getMessage();
                }
            }
        }

        boolean reordered = false;
        if (added > 0) {
            try {
                linkTracks(tracks, withInserted(api.listItems(accessToken, playlistId), inserted), true);
                reordered = true;
            } catch (Exception ignored) {
                // Insert responses already carry stable item ids; refresh can happen later.
            }
        } else {
            linkTracks(tracks, remote, true);
            reordered = true;
        }
        if (!reordered) {
            YoutubePlaylistOrder.sort(tracks);
            for (int i = 0; i < tracks.size(); i++) {
                tracks.get(i).position = i;
            }
        }
        if (!tracks.isEmpty()) {
            dao.updateAll(tracks);
        }
        return new MissingTracksResult(added, failed, firstError);
    }

    public DeleteResult deleteTracks(String localPlaylist, List<Track> selected,
                                     boolean deleteFiles, String accessToken)
            throws Exception {
        PlaylistSource source = requireEnabledSource(localPlaylist);
        String playlistId = playlistId(source);
        List<YoutubePlaylistApi.PlaylistItem> remote =
                api.listItems(accessToken, playlistId);
        SyncStore dao = store;
        List<Track> all = dao.getPlaylistSync(localPlaylist);
        linkTracks(all, remote, false);
        Map<Long, Track> byId = new HashMap<>();
        for (Track track : all) {
            byId.put(track.id, track);
        }

        List<Track> removable = new ArrayList<>();
        int failed = 0;
        String firstError = null;
        Set<String> removedRemoteIds = new HashSet<>();
        for (Track requested : selected) {
            Track current = byId.get(requested.id);
            if (current == null) {
                continue;
            }
            if (current.youtubePlaylistItemId == null) {
                removable.add(current); // already absent remotely or local-only
                continue;
            }
            try {
                api.deleteItem(accessToken, current.youtubePlaylistItemId);
                removedRemoteIds.add(current.youtubePlaylistItemId);
                removable.add(current);
            } catch (Exception e) {
                failed++;
                if (firstError == null) {
                    firstError = e.getMessage();
                }
            }
        }

        List<YoutubePlaylistApi.PlaylistItem> remainingRemote = new ArrayList<>();
        for (YoutubePlaylistApi.PlaylistItem item : remote) {
            if (!removedRemoteIds.contains(item.id)) {
                remainingRemote.add(item);
            }
        }
        store.runInTransaction(() -> {
            for (Track track : removable) {
                dao.delete(track);
                if (deleteFiles) {
                    deleteFileIfUnreferenced(dao, track.filePath);
                }
            }
            List<Track> remaining = dao.getPlaylistSync(localPlaylist);
            linkTracks(remaining, remainingRemote, true);
            if (!remaining.isEmpty()) {
                dao.updateAll(remaining);
            }
        });
        return new DeleteResult(removable, failed, firstError);
    }

    /** Links exact occurrences first, then matches previously unlinked duplicates in order. */
    static int linkTracks(List<Track> tracks,
                          List<YoutubePlaylistApi.PlaylistItem> remote,
                          boolean reorderLocally) {
        Map<String, YoutubePlaylistApi.PlaylistItem> byItemId = new HashMap<>();
        for (YoutubePlaylistApi.PlaylistItem item : remote) {
            byItemId.put(item.id, item);
        }
        Map<Long, YoutubePlaylistApi.PlaylistItem> matches = new HashMap<>();
        Set<String> used = new HashSet<>();
        for (Track track : tracks) {
            YoutubePlaylistApi.PlaylistItem exact = track.youtubePlaylistItemId != null
                    ? byItemId.get(track.youtubePlaylistItemId) : null;
            if (exact != null && equals(track.videoId, exact.videoId)) {
                matches.put(track.id, exact);
                used.add(exact.id);
            }
        }

        Map<String, Deque<YoutubePlaylistApi.PlaylistItem>> byVideo =
                new LinkedHashMap<>();
        List<YoutubePlaylistApi.PlaylistItem> sortedRemote = new ArrayList<>(remote);
        sortedRemote.sort(Comparator.comparingInt(item -> item.position));
        for (YoutubePlaylistApi.PlaylistItem item : sortedRemote) {
            if (!used.contains(item.id)) {
                byVideo.computeIfAbsent(item.videoId, ignored -> new ArrayDeque<>())
                        .addLast(item);
            }
        }
        for (Track track : tracks) {
            if (matches.containsKey(track.id) || track.videoId == null) {
                continue;
            }
            Deque<YoutubePlaylistApi.PlaylistItem> candidates = byVideo.get(track.videoId);
            if (candidates != null && !candidates.isEmpty()) {
                matches.put(track.id, candidates.removeFirst());
            }
        }

        for (Track track : tracks) {
            YoutubePlaylistApi.PlaylistItem match = matches.get(track.id);
            track.youtubePlaylistItemId = match != null ? match.id : null;
            track.youtubePosition = match != null ? match.position : null;
        }
        if (reorderLocally) {
            YoutubePlaylistOrder.sort(tracks);
            for (int i = 0; i < tracks.size(); i++) {
                tracks.get(i).position = i;
            }
        }
        return matches.size();
    }

    /**
     * Desktop: playlistItems.list lags behind inserts for a few seconds. Items it does not list
     * yet are put back at the positions the insert responses reported; otherwise the new tracks
     * would look removed on YouTube and drop to the end of the local playlist.
     */
    static List<YoutubePlaylistApi.PlaylistItem> withInserted(
            List<YoutubePlaylistApi.PlaylistItem> listed,
            List<YoutubePlaylistApi.PlaylistItem> inserted) {
        List<YoutubePlaylistApi.PlaylistItem> merged = new ArrayList<>(listed);
        for (YoutubePlaylistApi.PlaylistItem item : inserted) {
            boolean present = false;
            for (YoutubePlaylistApi.PlaylistItem existing : merged) {
                if (existing.id.equals(item.id)) {
                    present = true;
                    break;
                }
            }
            if (present) {
                continue;
            }
            for (int i = 0; i < merged.size(); i++) {
                YoutubePlaylistApi.PlaylistItem existing = merged.get(i);
                if (existing.position >= item.position) {
                    merged.set(i, new YoutubePlaylistApi.PlaylistItem(
                            existing.id, existing.videoId, existing.position + 1));
                }
            }
            merged.add(item);
        }
        return merged;
    }

    public static boolean hasMissingYoutubeTracks(@Nullable List<Track> tracks) {
        return tracks != null && !missingYoutubeTracks(tracks, null).isEmpty();
    }

    /** A copied YouTube track has no source-playlist position until it is linked remotely. */
    public static boolean hasLocallyAddedYoutubeTracks(@Nullable List<Track> tracks) {
        if (tracks == null) {
            return false;
        }
        for (Track track : tracks) {
            if (track.videoId != null && !track.videoId.isEmpty()
                    && track.youtubePosition == null) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasOriginalYoutubePlaylist(@Nullable PlaylistSource source) {
        if (source == null) {
            return false;
        }
        String playlistId = source.youtubePlaylistId;
        if (playlistId == null || playlistId.isEmpty()) {
            playlistId = YoutubeUrlParser.playlistId(source.sourceUrl);
        }
        return playlistId != null && !YoutubeUrlParser.isRadioPlaylistId(playlistId);
    }

    static List<Track> missingYoutubeTracks(List<Track> tracks,
                                            @Nullable List<String> requestedVideoIds) {
        Map<String, Integer> requestedCounts = null;
        if (requestedVideoIds != null) {
            requestedCounts = new HashMap<>();
            for (String videoId : requestedVideoIds) {
                if (videoId != null && !videoId.isEmpty()) {
                    requestedCounts.put(videoId,
                            requestedCounts.getOrDefault(videoId, 0) + 1);
                }
            }
        }
        List<Track> localOrder = new ArrayList<>(tracks);
        localOrder.sort(Comparator.comparingInt(track -> track.position));
        List<Track> missing = new ArrayList<>();
        for (Track track : localOrder) {
            if (track.videoId == null || track.videoId.isEmpty()
                    || track.youtubePlaylistItemId != null) {
                continue;
            }
            if (requestedCounts != null) {
                int remaining = requestedCounts.getOrDefault(track.videoId, 0);
                if (remaining <= 0) {
                    continue;
                }
                requestedCounts.put(track.videoId, remaining - 1);
            }
            missing.add(track);
        }
        return missing;
    }

    private PlaylistSource requireSource(String localPlaylist) {
        PlaylistSource source = store.getSync(localPlaylist);
        if (source == null) {
            throw new IllegalStateException("Этот плейлист не привязан к плейлисту YouTube");
        }
        return source;
    }

    private PlaylistSource requireEnabledSource(String localPlaylist) {
        PlaylistSource source = requireSource(localPlaylist);
        if (!source.youtubeSyncEnabled) {
            throw new IllegalStateException(
                    "Для этого плейлиста синхронизация с YouTube не включена");
        }
        return source;
    }

    private String playlistId(PlaylistSource source) {
        String playlistId = source.youtubePlaylistId != null
                ? source.youtubePlaylistId : YoutubeUrlParser.playlistId(source.sourceUrl);
        if (playlistId == null) {
            throw new IllegalStateException(
                    "Не удалось определить ID оригинального плейлиста YouTube");
        }
        return playlistId;
    }

    private static Track duplicate(Track source) {
        Track duplicate = new Track();
        duplicate.videoId = source.videoId;
        duplicate.title = source.title;
        duplicate.uploader = source.uploader;
        duplicate.durationMs = source.durationMs;
        duplicate.filePath = source.filePath;
        duplicate.playlistName = source.playlistName;
        duplicate.thumbnailUrl = source.thumbnailUrl;
        duplicate.createdAt = System.currentTimeMillis();
        duplicate.downloaded = true;
        duplicate.sourceUrl = source.sourceUrl;
        return duplicate;
    }

    private static Track placeholder(String playlist, String videoId, String url) {
        Track track = new Track();
        track.videoId = videoId;
        track.title = "YouTube " + videoId;
        track.filePath = "";
        track.playlistName = playlist;
        track.thumbnailUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        track.createdAt = System.currentTimeMillis();
        track.downloaded = false;
        track.sourceUrl = url;
        return track;
    }

    private static void deleteFileIfUnreferenced(SyncStore dao, String filePath) {
        if (filePath == null || filePath.isEmpty()
                || dao.countFileReferences(filePath) > 0) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        new File(filePath).delete();
    }

    private static boolean equals(@Nullable String first, @Nullable String second) {
        return first == null ? second == null : first.equals(second);
    }

    private static String truncate(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }
}
