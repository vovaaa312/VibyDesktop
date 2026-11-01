package com.viby.core.data;

/** Откуда скачан плейлист — для кнопки «обновить плейлист» (докачать новые треки). */
public class PlaylistSource {

    public String playlistName = "";

    public String sourceUrl = "";

    public long updatedAt;

    /** Stable YouTube playlist id extracted from sourceUrl. */
    public String youtubePlaylistId;

    /** Remote writes are opt-in and are never inferred from a cookie session. */
    public boolean youtubeSyncEnabled;

    /** Channel which owned the playlist when synchronization was enabled. */
    public String youtubeOwnerChannelId;
}
