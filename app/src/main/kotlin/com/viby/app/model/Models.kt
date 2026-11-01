package com.viby.app.model

/** UI models. They will be mapped from core entities once the SQLite library lands. */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val addedAt: Long = 0,
    /** null when the file is fully downloaded, otherwise 0..100. */
    val downloadProgress: Int? = null,
) {
    val isDownloaded: Boolean get() = downloadProgress == null
}

data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<Track>,
    val sourceUrl: String? = null,
    val youtubeSynced: Boolean = false,
) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs }
}

data class DownloadJob(
    val id: Long,
    val title: String,
    val playlistName: String,
    val totalTracks: Int,
    val doneTracks: Int,
    val currentTitle: String?,
    val currentProgress: Int,
    val paused: Boolean = false,
    val failedTracks: Int = 0,
) {
    val isFinished: Boolean get() = doneTracks >= totalTracks
}

enum class RepeatMode { OFF, ALL, ONE }

enum class SortMode(val label: String) {
    YOUTUBE("Порядок YouTube"),
    ADDED("Недавно добавленные"),
    TITLE("Название"),
    ARTIST("Исполнитель"),
    DURATION("Длительность"),
}
