package com.viby.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.viby.app.model.Playlist
import com.viby.app.model.SortMode
import com.viby.app.model.Track
import com.viby.core.VibyEngine
import com.viby.core.data.LibraryRepository
import com.viby.core.download.DownloadManager
import com.viby.core.download.DownloadManager.JobSnapshot
import com.viby.core.platform.AppDirs
import com.viby.core.playback.AudioEngine
import com.viby.core.tools.Tool
import com.viby.core.util.YoutubeUrlParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.viby.core.data.Track as CoreTrack

sealed interface Screen {
    data object Home : Screen
    data object Add : Screen
    data object Downloads : Screen
    data object Settings : Screen
    data class PlaylistScreen(val id: String) : Screen
}

enum class SidePanel { NONE, QUEUE, LYRICS }

/** Root UI state on top of [VibyEngine]. Mutations run on IO; state is published on the Swing thread. */
class AppState(val engine: VibyEngine = VibyEngine(AppDirs.current())) {
    /** UI-thread scope (coroutines-swing makes Dispatchers.Main the EDT). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val dirs: AppDirs get() = engine.dirs()
    val settings get() = engine.settings()
    val tools get() = engine.tools()

    val library = LibraryState(this)
    val player = PlayerState(EngineAudio(engine.audio()))
    val downloads = DownloadsState(this)

    var engineStatus by mutableStateOf(engine.status())
        private set

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    private val backStack = ArrayDeque<Screen>()
    var sidePanel by mutableStateOf(SidePanel.QUEUE)

    /** Transient snackbar text; consumed by the root composable. */
    var message by mutableStateOf<String?>(null)
        private set

    init {
        engine.addListener { status -> scope.launch { engineStatus = status } }
        engine.library().addListener { library.requestReload() }
        engine.downloads().addListener { jobs -> scope.launch { downloads.publish(jobs) } }
        library.requestReload()
        player.loader = ::loadTrack
        engine.audio().setListener(object : AudioEngine.Listener {
            override fun onEnded() {
                scope.launch { player.onEnded() }
            }

            override fun onError(message: String) {
                scope.launch {
                    showMessage(message)
                    player.onFailed()
                }
            }
        })
    }

    private var loadToken = 0

    /** Plays the local file, or resolves an online stream for tracks that are not downloaded yet. */
    private fun loadTrack(queued: Track, startMs: Long, paused: Boolean) {
        val token = ++loadToken
        val audio = engine.audio()
        // the queue holds snapshots; the track may have finished downloading since
        val track = library.findTrack(queued.id) ?: queued
        val file = track.filePath
        player.isLoading = false
        if (file != null && File(file).isFile) {
            audio.play(file, startMs, paused)
            return
        }
        val source = track.sourceUrl ?: track.videoId?.let { "https://www.youtube.com/watch?v=$it" }
        if (source == null || !engine.isReady) {
            showMessage(if (source == null) "Файл трека не найден" else "Подождите: идёт подготовка yt-dlp и FFmpeg")
            player.onFailed()
            return
        }
        audio.stop()
        player.isLoading = true
        showMessage("Трек ещё не скачан — играю онлайн")
        scope.launch {
            val url = runCatching { withContext(Dispatchers.IO) { engine.resolveStreamUrl(source) } }
            if (token != loadToken) return@launch
            player.isLoading = false
            url.onSuccess { audio.play(it, startMs, paused) }
                .onFailure {
                    showMessage("Не удалось получить поток: ${it.message ?: it.javaClass.simpleName}")
                    player.onFailed()
                }
        }
    }

    fun start() = engine.start()

    fun close() = runCatching { engine.close() }

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

    /** Runs blocking core work off the UI thread and reports failures as a toast. */
    fun io(block: () -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                showMessage(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun toolVersion(tool: Tool): String? = tools.installedVersion(tool)
}

private class EngineAudio(private val engine: AudioEngine) : AudioOutput {
    override fun pause() = engine.pause()
    override fun resume() = engine.resume()
    override fun seek(positionMs: Long) = engine.seek(positionMs)
    override fun stop() = engine.stop()
    override fun setVolume(volume: Float) = engine.setVolume(volume)
    override val positionMs: Long get() = engine.positionMs()
    override val hasSource: Boolean get() = engine.hasSource()
}

class LibraryState(private val app: AppState) {
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set

    private val reloads = Channel<Unit>(Channel.CONFLATED)

    init {
        app.scope.launch {
            for (ignored in reloads) {
                val fresh = withContext(Dispatchers.IO) { load(app.engine.library()) }
                playlists = withLiveProgress(fresh, app.downloads.jobs)
                loaded = true
                delay(100) // coalesce bursts of library notifications
            }
        }
    }

    /** Safe to call from any thread. */
    fun requestReload() {
        reloads.trySend(Unit)
    }

    fun playlist(id: String): Playlist? = playlists.firstOrNull { it.id == id }

    fun findTrack(id: String): Track? = playlists.firstNotNullOfOrNull { p -> p.tracks.firstOrNull { it.id == id } }

    fun create(name: String, onCreated: (String) -> Unit = {}) = app.io {
        if (!app.engine.library().createPlaylist(name)) error("Плейлист «$name» уже существует")
        app.scope.launch { onCreated(name) }
    }

    fun delete(name: String) = app.io { app.engine.library().deletePlaylist(name) }

    fun rename(from: String, to: String) = app.io {
        if (!app.engine.library().renamePlaylist(from, to)) error("Плейлист «$to» уже существует")
    }

    fun removeTracks(trackIds: Set<String>) = app.io {
        app.engine.library().removeTracks(trackIds.map(String::toLong))
    }

    fun moveTracks(trackIds: Set<String>, target: String) = app.io {
        app.engine.library().moveTracks(trackIds.map(String::toLong), target)
    }

    fun recentTracks(limit: Int): List<Track> =
        playlists.flatMap { it.tracks }.filter { it.isDownloaded }.sortedByDescending { it.addedAt }.take(limit)

    fun sorted(tracks: List<Track>, mode: SortMode): List<Track> = when (mode) {
        SortMode.YOUTUBE -> tracks.sortedWith(compareBy<Track, Int?>(nullsLast()) { it.youtubePosition })
        SortMode.ADDED -> tracks.sortedByDescending { it.addedAt }
        SortMode.TITLE -> tracks.sortedBy { it.title.lowercase() }
        SortMode.ARTIST -> tracks.sortedBy { it.artist.lowercase() }
        SortMode.DURATION -> tracks.sortedBy { it.durationMs }
    }

    /** Overlays the in-memory progress of the track being downloaded (DB writes are throttled). */
    fun applyLiveProgress(jobs: List<JobSnapshot>) {
        playlists = withLiveProgress(playlists, jobs)
    }

    private fun withLiveProgress(playlists: List<Playlist>, jobs: List<JobSnapshot>): List<Playlist> {
        val live = jobs.filter { it.activeTrackId >= 0 }.associate { it.activeTrackId.toString() to it.currentProgress }
        if (live.isEmpty()) return playlists
        return playlists.map { playlist ->
            if (playlist.tracks.none { it.id in live }) playlist
            else playlist.copy(tracks = playlist.tracks.map { track ->
                val progress = live[track.id]
                if (progress != null && !track.isDownloaded) track.copy(downloadProgress = progress, failed = null)
                else track
            })
        }
    }

    private fun load(repository: LibraryRepository): List<Playlist> =
        repository.playlists().map { summary ->
            Playlist(
                id = summary.name,
                name = summary.name,
                tracks = repository.tracks(summary.name).map { it.toUi() },
                sourceUrl = summary.sourceUrl,
                coverUrl = summary.coverUrl,
            )
        }

    private fun CoreTrack.toUi(): Track {
        val failedState = downloadState == CoreTrack.DOWNLOAD_FAILED_PERMANENT ||
            downloadState == CoreTrack.DOWNLOAD_FAILED_TEMPORARY
        return Track(
            id = id.toString(),
            title = title,
            artist = uploader ?: "",
            durationMs = durationMs,
            addedAt = createdAt,
            downloadProgress = if (downloaded) null else downloadProgress,
            thumbnailUrl = thumbnailUrl,
            filePath = filePath.takeIf { downloaded },
            videoId = videoId,
            sourceUrl = sourceUrl,
            youtubePosition = youtubePosition,
            failed = if (failedState) downloadErrorMessage ?: "Ошибка загрузки" else null,
        )
    }
}

/** Mirrors [DownloadManager] jobs into Compose state. */
class DownloadsState(private val app: AppState) {
    var jobs by mutableStateOf<List<JobSnapshot>>(emptyList())
        private set

    private var lastActiveTracks = emptySet<Long>()

    val activeCount: Int get() = jobs.count { !it.isFinished }

    internal fun publish(snapshot: List<JobSnapshot>) {
        jobs = snapshot
        app.library.applyLiveProgress(snapshot)
        // a finished track changes the library; the repository already notified, this covers progress only
        val active = snapshot.map { it.activeTrackId }.filter { it >= 0 }.toSet()
        if (active != lastActiveTracks) app.library.requestReload()
        lastActiveTracks = active
    }

    /** @param playlistName null = name the playlist after the YouTube title */
    fun enqueue(url: String, playlistName: String?, onQueued: () -> Unit = {}) {
        if (!app.engine.isReady) {
            app.showMessage("Подождите: идёт подготовка yt-dlp и FFmpeg")
            return
        }
        app.io {
            app.engine.downloads().enqueue(url, playlistName)
            app.scope.launch { onQueued() }
        }
    }

    /** Re-queues a failed or pending track; the manager reuses its placeholder row. */
    fun retry(track: Track, playlistName: String) {
        val videoId = track.videoId ?: return
        enqueue("https://www.youtube.com/watch?v=$videoId", playlistName) { app.showMessage("Загрузка поставлена в очередь") }
    }

    fun radio(track: Track) {
        val videoId = track.videoId ?: return app.showMessage("Радио доступно только для треков с YouTube")
        enqueue(YoutubeUrlParser.radioMixUrl(videoId), "Радио: ${track.title}") {
            app.showMessage("Собираю радио по треку «${track.title}»")
        }
    }

    fun pause(id: Long) = app.io { app.engine.downloads().pause(id) }
    fun resume(id: Long) = app.io { app.engine.downloads().resume(id) }
    fun cancel(id: Long) = app.io { app.engine.downloads().cancel(id) }
    fun clearFinished() = app.io { app.engine.downloads().clearFinished() }

    fun isPaused(job: JobSnapshot) = job.status == DownloadManager.Status.PAUSED
}
