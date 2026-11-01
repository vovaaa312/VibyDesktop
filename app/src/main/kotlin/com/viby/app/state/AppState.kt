package com.viby.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.viby.app.model.DownloadJob
import com.viby.app.model.Playlist
import com.viby.app.model.RepeatMode
import com.viby.app.model.SortMode
import com.viby.app.model.Track
import com.viby.core.platform.AppDirs
import com.viby.core.platform.Platform
import com.viby.core.settings.Settings
import com.viby.core.tools.BinaryManager
import com.viby.core.util.YoutubeUrlParser
import kotlinx.coroutines.delay

sealed interface Screen {
    data object Home : Screen
    data object Add : Screen
    data object Downloads : Screen
    data object Settings : Screen
    data class PlaylistScreen(val id: String) : Screen
}

enum class SidePanel { NONE, QUEUE, LYRICS }

/** Root UI state. Library, player and downloads are demo stubs until core is wired in. */
class AppState {
    val dirs: AppDirs = AppDirs.current()
    val settings = Settings(dirs.settingsFile(), dirs.defaultMusicDirectory())
    val tools = BinaryManager(dirs.tools(), Platform.current())

    val library = LibraryState()
    val player = PlayerState()
    val downloads = DownloadsState(library)

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    private val backStack = ArrayDeque<Screen>()
    var sidePanel by mutableStateOf(SidePanel.QUEUE)

    /** Transient snackbar text; consumed by the root composable. */
    var message by mutableStateOf<String?>(null)
        private set

    fun showMessage(text: String) {
        message = text
    }

    fun consumeMessage() {
        message = null
    }

    fun navigate(target: Screen) {
        if (target == screen) return
        backStack.addLast(screen)
        screen = target
    }

    fun back() {
        screen = backStack.removeLastOrNull() ?: return
    }

    val canGoBack: Boolean get() = backStack.isNotEmpty()

    fun toggleSidePanel(panel: SidePanel) {
        sidePanel = if (sidePanel == panel) SidePanel.NONE else panel
    }

    fun playPlaylist(playlist: Playlist, start: Track? = null, shuffle: Boolean = false) {
        val tracks = playlist.tracks
        if (tracks.isEmpty()) return
        player.shuffle = shuffle
        player.playQueue(tracks, start ?: if (shuffle) tracks.random() else tracks.first(), playlist.id)
    }
}

class LibraryState {
    val playlists = mutableStateListOf<Playlist>().apply { addAll(DemoData.playlists()) }

    fun playlist(id: String): Playlist? = playlists.firstOrNull { it.id == id }

    fun create(name: String): Playlist {
        val playlist = Playlist(id = "local-${System.nanoTime()}", name = name, tracks = emptyList())
        playlists.add(playlist)
        return playlist
    }

    fun delete(id: String) {
        playlists.removeAll { it.id == id }
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name) }

    fun removeTracks(playlistId: String, trackIds: Set<String>) =
        update(playlistId) { playlist -> playlist.copy(tracks = playlist.tracks.filterNot { it.id in trackIds }) }

    fun moveTracks(fromId: String, toId: String, trackIds: Set<String>) {
        val moving = playlist(fromId)?.tracks?.filter { it.id in trackIds } ?: return
        removeTracks(fromId, trackIds)
        update(toId) { it.copy(tracks = it.tracks + moving) }
    }

    fun addTrack(playlistId: String, track: Track) = update(playlistId) { it.copy(tracks = it.tracks + track) }

    fun updateTrack(playlistId: String, trackId: String, transform: (Track) -> Track) =
        update(playlistId) { playlist ->
            playlist.copy(tracks = playlist.tracks.map { if (it.id == trackId) transform(it) else it })
        }

    fun recentTracks(limit: Int): List<Track> =
        playlists.flatMap { it.tracks }.filter { it.isDownloaded }.sortedByDescending { it.addedAt }.take(limit)

    fun sorted(tracks: List<Track>, mode: SortMode): List<Track> = when (mode) {
        SortMode.YOUTUBE -> tracks
        SortMode.ADDED -> tracks.sortedByDescending { it.addedAt }
        SortMode.TITLE -> tracks.sortedBy { it.title.lowercase() }
        SortMode.ARTIST -> tracks.sortedBy { it.artist.lowercase() }
        SortMode.DURATION -> tracks.sortedBy { it.durationMs }
    }

    private fun update(id: String, transform: (Playlist) -> Playlist) {
        val index = playlists.indexOfFirst { it.id == id }
        if (index >= 0) playlists[index] = transform(playlists[index])
    }
}

/** Fake transport: advances a clock instead of decoding audio (real engine comes with stage 4). */
class PlayerState {
    val queue = mutableStateListOf<Track>()
    var index by mutableStateOf(-1)
        private set
    var contextPlaylistId by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
    var shuffle by mutableStateOf(false)
    var repeat by mutableStateOf(RepeatMode.OFF)
    var volume by mutableStateOf(0.8f)
    var muted by mutableStateOf(false)

    val current: Track? get() = queue.getOrNull(index)

    fun playQueue(tracks: List<Track>, start: Track, playlistId: String?) {
        val ordered = if (shuffle) listOf(start) + (tracks - start).shuffled() else tracks
        queue.clear()
        queue.addAll(ordered)
        contextPlaylistId = playlistId
        jumpTo(queue.indexOf(start))
    }

    fun jumpTo(newIndex: Int) {
        if (newIndex !in queue.indices) return
        index = newIndex
        positionMs = 0
        isPlaying = true
    }

    fun togglePlay() {
        if (current != null) isPlaying = !isPlaying
    }

    fun next(auto: Boolean = false) {
        if (queue.isEmpty()) return
        when {
            auto && repeat == RepeatMode.ONE -> positionMs = 0
            index + 1 < queue.size -> jumpTo(index + 1)
            repeat == RepeatMode.ALL -> jumpTo(0)
            else -> { isPlaying = false; positionMs = 0 }
        }
    }

    fun previous() {
        if (positionMs > 3_000 || index == 0) positionMs = 0 else jumpTo(index - 1)
    }

    fun seek(fraction: Float) {
        val track = current ?: return
        positionMs = (track.durationMs * fraction.coerceIn(0f, 1f)).toLong()
    }

    fun cycleRepeat() {
        repeat = RepeatMode.entries[(repeat.ordinal + 1) % RepeatMode.entries.size]
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        val track = current ?: return
        val upcoming = queue.drop(index + 1).let { if (shuffle) it.shuffled() else it }
        val head = queue.take(index + 1)
        queue.clear()
        queue.addAll(head + upcoming)
        index = queue.indexOf(track)
    }

    fun enqueueNext(tracks: List<Track>) {
        if (queue.isEmpty()) { playQueue(tracks, tracks.first(), null); return }
        queue.addAll(index + 1, tracks)
    }

    fun enqueue(tracks: List<Track>) {
        if (queue.isEmpty()) { playQueue(tracks, tracks.first(), null); return }
        queue.addAll(tracks)
    }

    fun removeFromQueue(position: Int) {
        if (position == index || position !in queue.indices) return
        queue.removeAt(position)
        if (position < index) index--
    }

    suspend fun runClock() {
        while (true) {
            delay(TICK_MS)
            val track = current ?: continue
            if (!isPlaying) continue
            positionMs += TICK_MS
            if (positionMs >= track.durationMs) next(auto = true)
        }
    }

    private companion object {
        const val TICK_MS = 250L
    }
}

/** Simulated queue so the downloads screen can be designed before DownloadManager exists. */
class DownloadsState(private val library: LibraryState) {
    val jobs = mutableStateListOf<DownloadJob>()
    private var nextId = 1L

    fun enqueue(url: String, playlistId: String): String? {
        val playlist = library.playlist(playlistId) ?: return "Плейлист не найден"
        val isPlaylist = YoutubeUrlParser.playlistId(url) != null && YoutubeUrlParser.videoId(url) == null
        if (!isPlaylist && YoutubeUrlParser.videoId(url) == null) return "Это не похоже на ссылку YouTube"
        val total = if (isPlaylist) 12 else 1
        jobs.add(
            DownloadJob(
                id = nextId++,
                title = if (isPlaylist) "Плейлист YouTube" else "Видео YouTube",
                playlistName = playlist.name,
                totalTracks = total,
                doneTracks = 0,
                currentTitle = "Получаю информацию…",
                currentProgress = 0,
            )
        )
        return null
    }

    fun togglePause(id: Long) = update(id) { it.copy(paused = !it.paused) }

    fun cancel(id: Long) {
        jobs.removeAll { it.id == id }
    }

    fun clearFinished() {
        jobs.removeAll { it.isFinished }
    }

    val activeCount: Int get() = jobs.count { !it.isFinished }

    suspend fun runSimulation() {
        while (true) {
            delay(200)
            val job = jobs.firstOrNull { !it.isFinished && !it.paused } ?: continue
            update(job.id) {
                val progress = it.currentProgress + 7
                if (progress < 100) it.copy(currentProgress = progress, currentTitle = "Трек ${it.doneTracks + 1}")
                else it.copy(doneTracks = it.doneTracks + 1, currentProgress = 0,
                    currentTitle = if (it.doneTracks + 1 >= it.totalTracks) null else "Трек ${it.doneTracks + 2}")
            }
        }
    }

    private fun update(id: Long, transform: (DownloadJob) -> DownloadJob) {
        val index = jobs.indexOfFirst { it.id == id }
        if (index >= 0) jobs[index] = transform(jobs[index])
    }
}
