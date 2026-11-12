package com.viby.app.state

import com.viby.app.model.Playlist
import com.viby.app.model.Track
import com.viby.app.state.LibrarySearch.TrackHit
import java.io.File

/** A library section opened as a track list (stage 4: favourites, history, artists, albums, folders). */
sealed interface CollectionRef {
    /** Queue context for the player, like a playlist id; never equal to a playlist name. */
    val id: String

    data object AllTracks : CollectionRef {
        override val id = "@all"
    }

    data object Favorites : CollectionRef {
        override val id = "@favorites"
    }

    data object Recent : CollectionRef {
        override val id = "@recent"
    }

    data class Artist(val key: String) : CollectionRef {
        override val id get() = "@artist:$key"
    }

    data class Album(val key: String) : CollectionRef {
        override val id get() = "@album:$key"
    }

    data class Folder(val key: String) : CollectionRef {
        override val id get() = "@folder:$key"
    }
}

/**
 * The whole library regrouped: every song once ([Track.key] collapses the copies kept in several
 * playlists), by artist, by album (tags, see core AlbumIndex) and by folder on disk.
 */
class LibraryIndex(playlists: List<Playlist>, albumByPath: Map<String, String> = emptyMap()) {

    /** A named group of songs: an artist, an album or a folder. */
    data class Group(val key: String, val name: String, val detail: String?, val tracks: List<TrackHit>) {
        val cover: Track? get() = tracks.firstOrNull { it.track.thumbnailUrl != null }?.track ?: tracks.firstOrNull()?.track
    }

    /** Every song once, in library order. */
    val tracks: List<TrackHit>
    val artists: List<Group>
    val albums: List<Group>
    val folders: List<Group>

    private val byKey: Map<String, TrackHit>

    init {
        val found = LinkedHashMap<String, MutableList<Pair<Track, Playlist>>>()
        for (playlist in playlists) {
            for (track in playlist.tracks) found.getOrPut(track.key) { mutableListOf() } += track to playlist
        }
        tracks = found.values.map { copies ->
            // the copy with a file on disk plays without a stream
            val best = copies.firstOrNull { it.first.isDownloaded && !it.first.fileMissing }
                ?: copies.firstOrNull { it.first.isDownloaded } ?: copies.first()
            TrackHit(best.first, best.second, copies.map { it.second }.distinctBy { it.id })
        }
        byKey = tracks.associateBy { it.track.key }

        artists = tracks.groupBy { artistName(it.track.artist).lowercase() }
            .map { (key, hits) -> Group(key, artistName(hits.first().track.artist), null, hits) }
            .sortedWith(compareBy<Group> { it.key == UNKNOWN_ARTIST.lowercase() }.thenBy { it.name.lowercase() })

        albums = tracks.mapNotNull { hit ->
            val album = hit.track.filePath?.let(albumByPath::get)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            album to hit
        }.groupBy({ (album, hit) -> "${album.lowercase()}\u0000${artistName(hit.track.artist).lowercase()}" }, { it })
            .map { (key, items) ->
                Group(key, items.first().first, artistName(items.first().second.track.artist), items.map { it.second })
            }
            .sortedBy { it.name.lowercase() }

        folders = tracks.filter { it.track.filePath != null }
            .groupBy { File(it.track.filePath!!).parent.orEmpty() }
            .map { (path, hits) -> Group(path.lowercase(), File(path).name.ifEmpty { path }, path, hits) }
            .sortedBy { it.name.lowercase() }
    }

    fun find(key: String): TrackHit? = byKey[key]

    fun artist(key: String): Group? = artists.firstOrNull { it.key == key }

    fun album(key: String): Group? = albums.firstOrNull { it.key == key }

    fun folder(key: String): Group? = folders.firstOrNull { it.key == key }

    companion object {
        const val UNKNOWN_ARTIST = "Неизвестный исполнитель"

        /** YouTube Music auto-channels are called "Artist - Topic". */
        fun artistName(uploader: String): String =
            uploader.trim().removeSuffix(" - Topic").removeSuffix("VEVO").trim().ifEmpty { UNKNOWN_ARTIST }
    }
}
