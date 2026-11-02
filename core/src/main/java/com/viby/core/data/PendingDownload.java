package com.viby.core.data;

/**
 * Незавершённое задание загрузки. Строка живёт, пока задание не закончилось,
 * поэтому после краша/перезагрузки/обновления приложения очередь
 * восстанавливается и докачивает с того же места.
 */
public class PendingDownload {

    public long id;

    public String url = "";

    public String playlistName;

    public boolean isPlaylist;

    public long createdAt;

    public long queuePosition;

    public boolean paused;

    public String trackOrderJson;

    /** Desktop: pre-resolved playlist entries (YouTube Data API), null when yt-dlp resolves. */
    public String entriesJson;
}
