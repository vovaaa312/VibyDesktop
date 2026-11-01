package com.viby.app.model

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
    val sourceUrl: String? = null,
    val youtubePosition: Int? = null,
    /** User-safe failure text when the download failed. */
    val failed: String? = null,
) {
    val isDownloaded: Boolean get() = downloadProgress == null
}

data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<Track>,
    val sourceUrl: String? = null,
    val coverUrl: String? = null,
    val youtubeSynced: Boolean = false,
) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs }
}

enum class RepeatMode { OFF, ALL, ONE }

enum class SortMode(val label: String) {
    YOUTUBE("Порядок YouTube"),
    ADDED("Недавно добавленные"),
    TITLE("Название"),
    ARTIST("Исполнитель"),
    DURATION("Длительность"),
}
