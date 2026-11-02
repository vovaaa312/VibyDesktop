package com.viby.core.download;

import com.viby.core.data.RoomTrackDao;
import com.viby.core.data.Track;
import com.viby.core.util.YoutubeUrlParser;

import org.jspecify.annotations.Nullable;

/**
 * The database half of Android's {@code DuplicateTrackPrompt}: a single video that is already
 * downloaded into the target playlist is not downloaded again; the user may instead add a second
 * entry that shares the existing file.
 */
public final class DuplicateTracks {

    private final RoomTrackDao dao;

    public DuplicateTracks(RoomTrackDao dao) {
        this.dao = dao;
    }

    public static String targetPlaylist(@Nullable String playlist) {
        return playlist == null || playlist.trim().isEmpty()
                ? DownloadService.DEFAULT_PLAYLIST : playlist.trim();
    }

    /** The downloaded track a confirmation should be asked about, or null to enqueue normally. */
    public @Nullable Track existing(String url, @Nullable String playlist, boolean isPlaylist) {
        String videoId = isPlaylist ? null : YoutubeUrlParser.videoId(url);
        if (videoId == null) {
            return null;
        }
        return dao.getDownloadedByVideoIdSync(targetPlaylist(playlist), videoId);
    }

    /**
     * Inserts another entry sharing the downloaded file of {@code videoId}.
     *
     * @return false when the source vanished meanwhile; the caller then enqueues normally
     */
    public boolean addDuplicate(String playlist, String videoId, String url) {
        Track source = dao.getDownloadedByVideoIdSync(playlist, videoId);
        if (source == null || source.filePath == null || source.filePath.isEmpty()) {
            return false;
        }

        Track duplicate = new Track();
        duplicate.videoId = source.videoId;
        duplicate.title = source.title;
        duplicate.uploader = source.uploader;
        duplicate.durationMs = source.durationMs;
        duplicate.filePath = source.filePath;
        duplicate.playlistName = playlist;
        duplicate.thumbnailUrl = source.thumbnailUrl;
        duplicate.position = dao.nextPosition(playlist);
        duplicate.youtubePosition = null;
        duplicate.createdAt = System.currentTimeMillis();
        duplicate.downloaded = true;
        duplicate.sourceUrl = source.sourceUrl != null ? source.sourceUrl : url;
        dao.insert(duplicate);
        return true;
    }
}
