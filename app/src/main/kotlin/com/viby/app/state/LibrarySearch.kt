package com.viby.app.state

import com.viby.app.model.Playlist
import com.viby.app.model.Track

/** Global search over every playlist: playlist names and tracks by title and artist. */
object LibrarySearch {

    /** One track found in the library; the same song in several playlists is listed once. */
    data class TrackHit(
        val track: Track,
        /** The playlist [track] comes from: playing it continues through this playlist. */
        val playlist: Playlist,
        val playlists: List<Playlist>,
    )

    data class Result(val playlists: List<Playlist>, val tracks: List<TrackHit>) {
        val isEmpty: Boolean get() = playlists.isEmpty() && tracks.isEmpty()
    }

    val EMPTY = Result(emptyList(), emptyList())

    /**
     * Every word of [query] must occur in the title or the artist ("кино группа" finds
     * "Группа крови — Кино"), case-insensitive and with ё = е. Titles starting with the query
     * come first, then matches at a word start, then the rest; ties keep the library order.
     */
    fun search(playlists: List<Playlist>, query: String, limit: Int = 300): Result {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return EMPTY
        val phrase = words.joinToString(" ")

        val foundPlaylists = playlists.filter { playlist -> normalize(playlist.name).let { name -> words.all(name::contains) } }

        val hits = LinkedHashMap<String, MutableList<Pair<Track, Playlist>>>()
        for (playlist in playlists) {
            for (track in playlist.tracks) {
                val text = normalize("${track.title} ${track.artist}")
                if (words.all(text::contains)) hits.getOrPut(identity(track)) { mutableListOf() } += track to playlist
            }
        }
        val tracks = hits.values
            .map { found ->
                // prefer the copy that is downloaded, so playing it does not start a stream
                val best = found.firstOrNull { it.first.isDownloaded } ?: found.first()
                TrackHit(best.first, best.second, found.map { it.second }.distinctBy { it.id })
            }
            .sortedBy { rank(normalize(it.track.title), normalize(it.track.artist), phrase) }
            .take(limit)
        return Result(foundPlaylists, tracks)
    }

    private fun rank(title: String, artist: String, phrase: String): Int = when {
        title.startsWith(phrase) -> 0
        artist.startsWith(phrase) -> 1
        title.contains(" $phrase") || artist.contains(" $phrase") -> 2
        else -> 3
    }

    /** Copies of one song share the video ID, or the file for local imports. */
    private fun identity(track: Track): String =
        track.videoId?.let { "yt:$it" } ?: track.filePath?.let { "file:${it.lowercase()}" } ?: "id:${track.id}"

    internal fun normalize(text: String): String =
        text.lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ").trim()
}
