package com.viby.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.viby.app.model.Track
import com.viby.core.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Избранное: songs the user marked with the heart. Kept in settings by [Track.key], not in the
 * database (its schema is shared with Android), so a song stays a favourite in every playlist it
 * is in and after the music folder moves; backups carry the list.
 */
class FavoritesState(private val settings: Settings) {

    /** Song key → when it was liked. */
    var liked by mutableStateOf(load(settings.getJson(KEY)))
        private set

    fun isFavorite(track: Track): Boolean = track.key in liked

    fun toggle(track: Track) = set(listOf(track), !isFavorite(track))

    fun set(tracks: List<Track>, favorite: Boolean) {
        val now = System.currentTimeMillis()
        liked = if (favorite) {
            // several tracks liked at once keep their order: the first one is the newest
            liked + tracks.mapIndexed { i, track -> track.key to (liked[track.key] ?: (now - i)) }
        } else {
            liked - tracks.map { it.key }.toSet()
        }
        settings.putJson(KEY, JSONObject(liked))
    }

    /** A restored backup merged its favourites into the settings. */
    fun reload() {
        liked = load(settings.getJson(KEY))
    }

    companion object {
        const val KEY = "favorites"

        internal fun load(json: JSONObject): Map<String, Long> =
            json.keySet().associateWith { json.optLong(it) }
    }
}

/** Недавно прослушанные: the last [LIMIT] songs that were started, newest first. */
class HistoryState(private val settings: Settings) {

    /** Song key → when it was last played. */
    var played by mutableStateOf(FavoritesState.load(settings.getJson(KEY)))
        private set

    /** Keys, most recently played first. */
    val recent: List<String> get() = played.entries.sortedByDescending { it.value }.map { it.key }

    fun record(track: Track) {
        val updated = played + (track.key to System.currentTimeMillis())
        played = if (updated.size <= LIMIT) updated
        else updated.entries.sortedByDescending { it.value }.take(LIMIT).associate { it.key to it.value }
        settings.putJson(KEY, JSONObject(played))
    }

    companion object {
        const val KEY = "recentlyPlayed"
        const val LIMIT = 200
    }
}

/**
 * Album tags for the "Альбомы" section: shown from the cache at once, new files are read in the
 * background after the library changes (core AlbumIndex).
 */
class AlbumsState(private val app: AppState) {

    /** File path → album. */
    var byPath by mutableStateOf(app.engine.albums().albums())
        private set

    /** "Читаю теги… 40 из 520" while files are being read, null otherwise. */
    var progress by mutableStateOf<String?>(null)
        private set

    private var running = false
    private var again = false

    /** Called after every library reload; reads only files the index has not seen. */
    fun refresh() {
        // without ffprobe every file would be cached as "no album"
        if (!app.engine.isReady) return
        if (running) {
            again = true
            return
        }
        running = true
        val files = app.library.playlists.flatMap { it.tracks }.mapNotNull { it.filePath }.distinct()
        app.scope.launch {
            try {
                val read = withContext(Dispatchers.IO) {
                    app.engine.albums().refresh(files) { done, total ->
                        if (total > 20 && (done % 10 == 0 || done == total)) {
                            app.scope.launch { progress = "Читаю теги альбомов… $done из $total" }
                        }
                    }
                }
                if (read > 0 || byPath.isEmpty()) byPath = withContext(Dispatchers.IO) { app.engine.albums().albums() }
            } catch (e: Exception) {
                // albums are optional: the section just stays as it was
            } finally {
                progress = null
                running = false
                if (again) {
                    again = false
                    refresh()
                }
            }
        }
    }
}
