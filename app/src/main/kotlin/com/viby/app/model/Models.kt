package com.viby.app.model

import com.viby.core.util.YoutubeUrlParser

/** UI models mapped from core entities by LibraryState. */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val addedAt: Long = 0,
    /** null when the file is fully downloaded, otherwise 0..100. */
    val downloadProgress: Int? = null,
    val thumbnailUrl: String? = null,
    val filePath: String? = null,
    val videoId: String? = null,
    val playlistName: String = "",
    val sourceUrl: String? = null,
    val youtubePosition: Int? = null,
    /** User-safe failure text when the download failed. */
    val failed: String? = null,
    /** Downloaded, but the file is not on disk any more (folder moved, deleted, drive unplugged). */
    val fileMissing: Boolean = false,
) {
    val isDownloaded: Boolean get() = downloadProgress == null

    /**
     * The song behind this row: copies in several playlists share it, and it survives moving
     * the music folder (favourites and the listening history are stored by it).
     */
    val key: String
        get() = videoId?.let { "yt:$it" }
            ?: filePath?.let { path ->
                val file = java.io.File(path)
                "file:${file.parentFile?.name.orEmpty()}/${file.name}".lowercase()
            }
            ?: "id:$id"
}

data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<Track>,
    val sourceUrl: String? = null,
    val coverUrl: String? = null,
    /** Owned or imported YouTube playlist this one can sync with (radio mixes excluded). */
    val youtubePlaylistId: String? = null,
) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs }
    val canSync: Boolean get() = youtubePlaylistId != null && !youtubePlaylistId.startsWith("RD")

    /** Android isUnsavedRadio: a track radio not yet saved as a playlist on YouTube. */
    val isUnsavedRadio: Boolean
        get() = YoutubeUrlParser.isRadioMixUrl(sourceUrl) && !canSync
}

enum class RepeatMode { OFF, ALL, ONE }

enum class SortMode(val label: String) {
    YOUTUBE("Порядок плейлиста"),
    ADDED("Недавно добавленные"),
    TITLE("Название"),
    ARTIST("Исполнитель"),
    DURATION("Длительность"),
}

/** Which tracks a playlist shows; kept per playlist together with the sort (PlaylistView). */
enum class TrackFilter(val label: String) {
    ALL("Все треки"),
    NOT_DOWNLOADED("Не загружено"),
    FAILED("С ошибкой"),
    MISSING("Нет файла");

    fun matches(track: Track): Boolean = when (this) {
        ALL -> true
        NOT_DOWNLOADED -> !track.isDownloaded
        FAILED -> track.failed != null
        MISSING -> track.fileMissing
    }
}

data class PlaylistView(val sort: SortMode = SortMode.YOUTUBE, val filter: TrackFilter = TrackFilter.ALL)
