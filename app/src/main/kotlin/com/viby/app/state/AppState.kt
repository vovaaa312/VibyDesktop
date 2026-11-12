package com.viby.app.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.viby.app.model.Playlist
import com.viby.app.model.PlaylistView
import com.viby.app.model.SortMode
import com.viby.app.model.Track
import com.viby.app.model.TrackFilter
import com.viby.app.theme.AccentTheme
import com.viby.app.theme.VibyColors
import com.viby.app.ui.tracksLabel
import com.viby.core.VibyEngine
import com.viby.core.data.LibraryRepository
import com.viby.core.download.DownloadService
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
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import com.viby.core.data.Track as CoreTrack

sealed interface Screen {
    data object Home : Screen
    data object Add : Screen
    data object Downloads : Screen
    data object Settings : Screen
    data object Equalizer : Screen
    data object YoutubePlaylists : Screen
    data object Storage : Screen
    data object Search : Screen
    /** Медиатека: all tracks, artists, albums, folders, recently played ([AppState.libraryTab]). */
    data object Library : Screen
    data class Collection(val ref: CollectionRef) : Screen
    data class PlaylistScreen(val id: String) : Screen
}

enum class SidePanel { NONE, QUEUE, LYRICS }

enum class LibraryTab(val label: String) {
    TRACKS("Треки"), ARTISTS("Исполнители"), ALBUMS("Альбомы"), FOLDERS("Папки"), RECENT("Недавние")
}

/** A [CollectionRef] resolved against the current library. */
class CollectionView(val kind: String, val title: String, val detail: String?, val tracks: List<LibrarySearch.TrackHit>)

private const val THEME_KEY = "theme"
/** Queue context of the home screen shelves; "@" marks it as not a playlist. */
private const val SHELF_CONTEXT = "@home"
private const val STREAM_RETRY_INTERVAL_MS = 30_000L

/** Formats ffmpeg decodes that the import dialog offers. */
/** Settings key: playlist id → saved sort and filter. */
private const val VIEWS_KEY = "playlistViews"

private val AUDIO_EXTENSIONS = listOf("mp3", "m4a", "aac", "opus", "ogg", "flac", "wav", "webm", "wma")

class PromptAction(val label: String, val run: () -> Unit)

class Prompt(val title: String, val text: String?, val actions: List<PromptAction>)

/** Toast text; [details] is the raw error output, opened by "Подробнее". */
class Notice(val text: String, val details: String? = null)

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
    val account = AccountState(this)
    val updates = UpdateState(this)
    val storage = StorageState(this)
    val favorites = FavoritesState(settings)
    val history = HistoryState(settings)
    val albums = AlbumsState(this)

    var libraryTab by mutableStateOf(LibraryTab.TRACKS)

    val sleepTimer = SleepTimer(scope) {
        player.pause()
        showMessage("Таймер сна: воспроизведение остановлено")
    }

    var engineStatus by mutableStateOf(engine.status())
        private set

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    private val backStack = ArrayDeque<Screen>()
    var sidePanel by mutableStateOf(SidePanel.QUEUE)

    /** Open question dialog; shown by the root composable. */
    var prompt by mutableStateOf<Prompt?>(null)
        private set

    /** Transient snackbar text; consumed by the root composable. */
    var message by mutableStateOf<Notice?>(null)
        private set

    /** Raw error text shown in a closable dialog with a copy button. */
    var errorDetails by mutableStateOf<String?>(null)

    init {
        VibyColors.theme = AccentTheme.entries.firstOrNull { it.name == settings.getString(THEME_KEY, "") }
            ?: AccentTheme.VIBY
        engine.addListener { status ->
            scope.launch {
                engineStatus = status
                // album tags need ffprobe, which is ready only now on a first start
                if (library.loaded) albums.refresh()
            }
        }
        engine.library().addListener { library.requestReload() }
        engine.downloads().addListener(downloads::onJobsChanged)
        library.requestReload()
        player.loader = { track, startMs, paused -> loadTrack(track, startMs, paused) }
        player.onStateChanged = ::schedulePlaybackSave
        engine.ytDlp().setCookieFailureListener {
            scope.launch {
                if (!cookieWarningShown) {
                    cookieWarningShown = true
                    showMessage(
                        "Не удалось прочитать куки из браузера — качаю без входа. " +
                            "Закройте браузер или выберите Firefox / cookies.txt в настройках"
                    )
                }
            }
        }
        engine.audio().setListener(object : AudioEngine.Listener {
            override fun onEnded() {
                scope.launch {
                    if (sleepTimer.consumeTrackEnd()) {
                        player.finishAndStop()
                        showMessage("Таймер сна: трек закончился, воспроизведение остановлено")
                    } else {
                        player.onEnded()
                    }
                }
            }

            override fun onError(message: String) {
                scope.launch {
                    if (retryStream()) return@launch
                    showError(message, "Не удалось воспроизвести трек")
                    player.onFailed()
                }
            }
        })
    }

    private var loadToken = 0
    private var cookieWarningShown = false
    /** The current source is an online stream (its URL expires and may answer 403). */
    private var streaming = false
    private var streamRetriedAt = 0L

    /** Plays the local file, or resolves an online stream for tracks that are not downloaded yet. */
    private fun loadTrack(queued: Track, startMs: Long, paused: Boolean, retry: Boolean = false) {
        val token = ++loadToken
        val audio = engine.audio()
        if (!retry) streamRetriedAt = 0L
        // the queue holds snapshots; the track may have finished downloading since
        val track = library.findTrack(queued.id) ?: queued
        val file = track.filePath
        player.isLoading = false
        streaming = false
        if (!paused && !retry) history.record(track)
        if (file != null && File(file).isFile) {
            audio.play(file, startMs, paused)
            return
        }
        if (track.isDownloaded) {
            // the UI snapshot may be older than the database (files were just moved or relinked)
            val fresh = runCatching { engine.library().track(track.id.toLong())?.filePath }.getOrNull()
            if (!fresh.isNullOrEmpty() && File(fresh).isFile) {
                audio.play(fresh, startMs, paused)
                return
            }
        }
        val source = track.sourceUrl ?: track.videoId?.let { "https://www.youtube.com/watch?v=$it" }
        if (source == null || !engine.isReady) {
            showMessage(
                if (source == null) "Файл трека не найден. Проверьте папку с музыкой: Настройки → Хранилище → «Найти»"
                else "Подождите: идёт подготовка yt-dlp и FFmpeg"
            )
            player.onFailed()
            return
        }
        audio.stop()
        player.isLoading = true
        streaming = true
        if (track.isDownloaded) {
            // downloaded once, but the file is gone: say so instead of pretending it never was
            if (!retry) showMessage("Файл трека не найден — играю онлайн. Вернуть файлы: Настройки → Хранилище → «Найти»")
        } else {
            if (!retry) showMessage("Трек ещё не скачан — играю онлайн и скачиваю его первым")
            // Android: a pending track that starts playing jumps to the front of the download queue
            if (track.videoId != null && track.playlistName.isNotEmpty()) downloads.prioritize(track)
        }
        scope.launch {
            val url = runCatching { withContext(Dispatchers.IO) { engine.resolveStreamUrl(source) } }
            if (token != loadToken) return@launch
            player.isLoading = false
            url.onSuccess { audio.play(debugBreakStream(it, retry), startMs, paused) }
                .onFailure {
                    showError(it, "Не удалось получить поток")
                    player.onFailed()
                }
        }
    }

    /**
     * Manual test of the 403 handling: VIBY_DEBUG_STREAM_403=once spoils the first stream URL of a
     * track (the retry recovers), =always spoils every URL (the error toast appears).
     */
    private fun debugBreakStream(url: String, retry: Boolean): String = when (System.getenv("VIBY_DEBUG_STREAM_403")) {
        "always" -> url.replace("sig=", "sig=x")
        "once" -> if (retry) url else url.replace("sig=", "sig=x")
        else -> url
    }

    /**
     * A stream URL from yt-dlp expires and YouTube may refuse it (403), e.g. on a seek after a
     * while. Plays the downloaded file if it is ready now, else resolves a fresh URL; a second
     * failure right after the retry is reported instead of looping.
     */
    private fun retryStream(): Boolean {
        val track = player.current ?: return false
        val now = System.currentTimeMillis()
        if (!streaming || now - streamRetriedAt < STREAM_RETRY_INTERVAL_MS) return false
        streamRetriedAt = now
        loadTrack(track, player.positionMs, !player.isPlaying, retry = true)
        return true
    }

    fun start() = engine.start()

    fun close() = runCatching {
        savePlaybackNow()
        engine.close()
    }

    /** Settings a backup restored take effect at once: accent theme and equalizer. */
    fun reloadSettings() {
        VibyColors.theme = AccentTheme.entries.firstOrNull { it.name == settings.getString(THEME_KEY, "") }
            ?: AccentTheme.VIBY
        engine.equalizer().reload()
        favorites.reload()
    }

    /** Settings → Оформление: switches the accent everywhere at once and remembers it. */
    fun setTheme(theme: AccentTheme) {
        VibyColors.theme = theme
        settings.putString(THEME_KEY, theme.name)
    }

    fun showMessage(text: String) {
        message = Notice(text)
    }

    /** A short readable error in the toast; the raw text stays under "Подробнее". */
    fun showError(raw: String?, context: String? = null) {
        val summary = ErrorText.humanize(raw)
        val details = raw?.trim()?.takeIf { it.isNotEmpty() && it != summary }
        message = Notice(context?.let { "$it: $summary" } ?: summary, details)
    }

    fun showError(error: Throwable, context: String? = null) =
        showError(error.message ?: error.javaClass.simpleName, context)

    /** Question dialog (Android MaterialAlertDialogBuilder): one action = confirm, several = a list. */
    fun ask(title: String, text: String?, vararg actions: PromptAction) {
        prompt = Prompt(title, text, actions.toList())
    }

    fun answer(action: PromptAction?) {
        prompt = null
        action?.run?.invoke()
    }

    /**
     * Android YoutubeSyncController.addTrackByUrl: a single video for a playlist with a YouTube original may be
     * added to its YouTube copy too (at the top, like the YouTube app), or only locally.
     */
    fun addByUrl(url: String, playlistName: String?, onAccepted: (queued: Boolean) -> Unit) {
        val clean = url.trim()
        val synced = playlistName?.takeIf { library.playlist(it)?.canSync == true }
        val single = !DownloadService.isPlaylistUrl(clean) && YoutubeUrlParser.videoId(clean) != null
        if (synced == null || !single || !account.isSignedIn) {
            downloads.enqueueChecked(clean, playlistName, onAccepted)
            return
        }
        ask(
            "Добавить трек", null,
            PromptAction("В Viby и YouTube") {
                val addToYoutube = { account.addTrackAtStart(synced, clean, onAccepted) }
                downloads.confirmIfDuplicate(clean, synced, onUnique = addToYoutube) { addToYoutube() }
            },
            PromptAction("Только в Viby") { downloads.enqueueChecked(clean, synced, onAccepted) },
        )
    }

    /**
     * Android deleteTracksWithChoice: tracks of a playlist with a YouTube original may be removed
     * there too. Desktop asks whenever the account is signed in, without a separate sync switch.
     */
    fun deleteTracks(playlist: Playlist, tracks: List<Track>, deleteFiles: Boolean) {
        val ids = tracks.map { it.id }.toSet()
        val localDelete = { library.removeTracks(ids, deleteFiles) }
        if (!playlist.canSync || !account.isSignedIn || tracks.none { it.videoId != null }) {
            localDelete()
            return
        }
        ask(
            "Удалить также на YouTube?", null,
            PromptAction("Из Viby и YouTube") { account.deleteSynced(playlist.name, ids, deleteFiles) },
            PromptAction("Только в Viby") { localDelete() },
        )
    }

    /**
     * "Send to playlist" (Android offerTrackCopy): into a playlist with an original YouTube
     * playlist the user chooses whether the tracks go to YouTube as well.
     */
    fun sendTracks(tracks: List<Track>, target: Playlist, onDone: () -> Unit = {}) {
        val ids = tracks.map { it.id }.toSet()
        val videoIds = tracks.filter { it.playlistName != target.name }.mapNotNull { it.videoId }.distinct()
        val copyLocally = { toYoutube: Boolean ->
            library.copyTracks(ids, target.id) { added ->
                showMessage(
                    if (added == 0) "Эти треки уже есть в «${target.name}»"
                    else "${tracksLabel(added)} → «${target.name}»"
                )
                if (toYoutube) account.copyToYoutube(target.name, videoIds)
            }
            onDone()
        }
        if (!target.canSync || videoIds.isEmpty() || !account.isSignedIn) {
            copyLocally(false)
            return
        }
        ask(
            "Добавить в «${target.name}»", null,
            PromptAction("В Viby и YouTube") { copyLocally(true) },
            PromptAction("Только в Viby") { copyLocally(false) },
        )
    }

    fun consumeMessage() {
        message = null
    }

    /** The global search text; kept while browsing so "Назад" returns to the same results. */
    var searchQuery by mutableStateOf("")

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

    /**
     * Android PlaybackQueueController.playLocalTrack / playResolvedStream: the queue holds the
     * playable (downloaded) tracks; a pending start track plays as a stream inside a custom queue.
     */
    /** The playlist's tracks in the sort the user picked for it: the queue follows what the screen shows. */
    fun ordered(playlist: Playlist): List<Track> = library.sorted(playlist.tracks, library.view(playlist.id).sort)

    /** Re-orders a queue built from [playlistId] after its sort changed or its tracks did. */
    internal fun resyncQueue(playlistId: String) {
        if (player.contextPlaylistId != playlistId) return
        library.playlist(playlistId)?.let { player.syncWithPlaylist(playlistId, playable(ordered(it))) }
    }

    fun playPlaylist(playlist: Playlist, start: Track? = null, shuffle: Boolean = false) =
        playTracks(playlist.id, ordered(playlist), start, shuffle)

    /** A shelf on the home screen: the queue is the shelf, starting at the clicked track. */
    fun playShelf(tracks: List<Track>, start: Track) = playTracks(SHELF_CONTEXT, tracks, start, shuffle = false)

    /** Plays a library section in the order it is shown; the queue is exactly that list. */
    fun playCollection(ref: CollectionRef, tracks: List<Track>, start: Track? = null, shuffle: Boolean = false) =
        playTracks(ref.id, tracks, start, shuffle)

    private fun playTracks(contextId: String, tracks: List<Track>, start: Track?, shuffle: Boolean) {
        val playable = playable(tracks)
        if (shuffle) player.setShuffleEnabled(true)
        if (start != null && !isPlayableLocal(start)) {
            // keep the pending track at its place among the local ones
            val queue = tracks.filter { it.id == start.id || isPlayableLocal(it) }
            player.playWithPending(queue, start, contextId)
            return
        }
        val lost = tracks.count { it.fileMissing }
        if (playable.isEmpty()) {
            if (lost > 0) {
                ask(
                    "Файлы плейлиста не найдены",
                    "У ${tracksLabel(lost)} нет файла на диске: папку с музыкой перенесли, переименовали или удалили. " +
                        "Viby поищет файлы в папке с музыкой, а чего не найдёт — предложит скачать заново.",
                    PromptAction("Найти файлы") { storage.scan(adopt = false) },
                )
            } else {
                showMessage("В плейлисте пока нет скачанных треков")
            }
            return
        }
        val first = start ?: if (shuffle) playable.random() else playable.first()
        // a section has no stored order to follow: what is on screen replaces the queue
        if (contextId.startsWith("@")) player.replaceQueue(playable, first, contextId)
        else player.playQueue(playable, first, contextId)
        if (start == null && lost > 0) {
            showMessage("Пропущено: ${tracksLabel(lost)} без файла на диске. Настройки → Хранилище → «Найти»")
        }
    }

    /**
     * Stops the decoder so the playing file can be moved (Windows locks an open file); the
     * returned action resumes at the same position, from wherever the file is by then.
     */
    fun releasePlayingFile(): () -> Unit {
        if (!engine.audio().hasSource()) return {}
        engine.audio().stop()
        return {
            if (!engine.audio().hasSource()) player.current?.let { loadTrack(it, player.positionMs, !player.isPlaying) }
        }
    }

    /** Android PlaybackQueueController.isPlayableLocal: downloaded and the file is still there. */
    fun isPlayableLocal(track: Track): Boolean =
        track.isDownloaded && track.filePath != null && !track.fileMissing

    fun playable(tracks: List<Track>): List<Track> = tracks.filter(::isPlayableLocal)

    /** Android PlaybackQueueController.resetToActivePlaylist. */
    fun resetQueue() {
        val playlist = player.contextPlaylistId?.let(library::playlist)
        player.resetTo(playable(playlist?.let(::ordered).orEmpty()), playlist?.id)
        showMessage("Очередь сброшена")
    }

    private var saveJob: kotlinx.coroutines.Job? = null

    /** Android queueSaver: persist 300 ms after the last change. */
    private fun schedulePlaybackSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            savePlaybackNow()
        }
    }

    private fun savePlaybackNow() {
        runCatching { settings.putJson("playback", player.saveState()) }
    }

    private var playbackRestored = false

    /** Restores the last queue once the library is loaded (PlaybackStateStore.restore). */
    internal fun onLibraryLoaded() {
        albums.refresh()
        if (playbackRestored) {
            player.contextPlaylistId?.let(::resyncQueue)
            return
        }
        playbackRestored = true
        player.restoreState(settings.getJson("playback")) { id -> library.findTrack(id)?.takeIf(::isPlayableLocal) }
    }

    /** Runs blocking core work off the UI thread and reports failures as a toast. */
    fun io(block: () -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                showError(e)
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
                // the database is closed while the app shuts down; a late reload must not crash
                val fresh = runCatching { withContext(Dispatchers.IO) { load(app.engine.library()) } }.getOrNull() ?: continue
                playlists = withLiveProgress(fresh, app.downloads.jobs)
                loaded = true
                app.onLibraryLoaded()
                delay(100) // coalesce bursts of library notifications
            }
        }
    }

    /** Safe to call from any thread. */
    fun requestReload() {
        reloads.trySend(Unit)
    }

    /** The library regrouped by song, artist, album and folder; rebuilt when it or the album tags change. */
    val index: LibraryIndex by derivedStateOf { LibraryIndex(playlists, app.albums.byPath) }

    /** What [ref] holds right now; null when it is gone (the last track of an artist was deleted). */
    fun collection(ref: CollectionRef): CollectionView? = when (ref) {
        CollectionRef.AllTracks -> CollectionView("Медиатека", "Все треки", null, index.tracks)
        CollectionRef.Favorites -> {
            val liked = app.favorites.liked
            CollectionView("Подборка", "Избранное", null,
                index.tracks.filter { it.track.key in liked }.sortedByDescending { liked[it.track.key] })
        }
        CollectionRef.Recent ->
            CollectionView("Подборка", "Недавно прослушанные", null, app.history.recent.mapNotNull(index::find))
        is CollectionRef.Artist -> index.artist(ref.key)?.let { CollectionView("Исполнитель", it.name, null, it.tracks) }
        is CollectionRef.Album -> index.album(ref.key)?.let { CollectionView("Альбом", it.name, it.detail, it.tracks) }
        is CollectionRef.Folder -> index.folder(ref.key)?.let { CollectionView("Папка", it.name, it.detail, it.tracks) }
    }

    fun playlist(id: String): Playlist? = playlists.firstOrNull { it.id == id }

    fun findTrack(id: String): Track? = playlists.firstNotNullOfOrNull { p -> p.tracks.firstOrNull { it.id == id } }

    fun create(name: String, onCreated: (String) -> Unit = {}) = app.io {
        if (!app.engine.library().createPlaylist(name)) error("Плейлист «$name» уже существует")
        app.scope.launch { onCreated(name) }
    }

    fun delete(name: String) {
        app.player.clearIfLoaded(name)
        app.io { app.engine.library().deletePlaylist(name) }
    }

    fun rename(from: String, to: String) = app.io {
        if (!app.engine.library().renamePlaylist(from, to)) error("Плейлист «$to» уже существует")
        app.scope.launch {
            view(from).takeIf { it != PlaylistView() }?.let { saveView(to, it); saveView(from, PlaylistView()) }
            app.player.renameContext(from, to)
        }
    }

    /** The sort and filter the user picked for [playlistId], remembered across restarts. */
    fun view(playlistId: String): PlaylistView {
        val saved = app.settings.getJson(VIEWS_KEY)?.optJSONObject(playlistId) ?: return PlaylistView()
        return PlaylistView(
            sort = SortMode.entries.firstOrNull { it.name == saved.optString("sort") } ?: SortMode.YOUTUBE,
            filter = TrackFilter.entries.firstOrNull { it.name == saved.optString("filter") } ?: TrackFilter.ALL,
        )
    }

    fun saveView(playlistId: String, view: PlaylistView) {
        val views = app.settings.getJson(VIEWS_KEY) ?: org.json.JSONObject()
        if (view == PlaylistView()) {
            views.remove(playlistId)
        } else {
            views.put(playlistId, org.json.JSONObject().put("sort", view.sort.name).put("filter", view.filter.name))
        }
        app.settings.putJson(VIEWS_KEY, views)
        app.resyncQueue(playlistId)
    }

    fun removeTracks(trackIds: Set<String>, deleteFiles: Boolean = false) = app.io {
        app.engine.library().removeTracks(trackIds.map(String::toLong), deleteFiles)
    }

    /** Android "import files" (OpenMultipleDocuments): copies picked audio files into [playlist]. */
    fun importFiles(playlist: String) {
        val dialog = FileDialog(null as Frame?, "Добавить файлы в «$playlist»", FileDialog.LOAD).apply {
            isMultipleMode = true
            // Windows ignores the filename filter but takes a wildcard list as the initial name
            file = AUDIO_EXTENSIONS.joinToString(";") { "*.$it" }
            setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS }
            isVisible = true
        }
        val files = dialog.files.map { it.toPath() }
        if (files.isEmpty()) return
        app.showMessage("Импортирую…")
        app.io {
            val added = app.engine.files().importFiles(files, playlist)
            app.scope.launch { app.showMessage("Добавлено: $added") }
        }
    }

    /** "Send to playlist": copies tracks (shared files) and reports how many were added. */
    fun copyTracks(trackIds: Set<String>, target: String, onCopied: (Int) -> Unit = {}) = app.io {
        val added = app.engine.library().copyTracks(trackIds.map(String::toLong), target)
        app.scope.launch { onCopied(added) }
    }

    fun recentTracks(limit: Int): List<Track> =
        playlists.flatMap { it.tracks }.filter { it.isDownloaded }.sortedByDescending { it.addedAt }.take(limit)

    fun visible(tracks: List<Track>, view: PlaylistView): List<Track> =
        sorted(tracks, view.sort).filter(view.filter::matches)

    fun sorted(tracks: List<Track>, mode: SortMode): List<Track> = when (mode) {
        // playlist order as stored: YouTube order for imported playlists, sent tracks on top
        SortMode.YOUTUBE -> tracks
        SortMode.ADDED -> tracks.sortedByDescending { it.addedAt }
        SortMode.TITLE -> tracks.sortedBy { it.title.lowercase() }
        SortMode.ARTIST -> tracks.sortedBy { it.artist.lowercase() }
        SortMode.DURATION -> tracks.sortedBy { it.durationMs }
    }

    /** Overlays the in-memory progress of the track being downloaded (DB writes are throttled). */
    fun applyLiveProgress(jobs: List<JobUi>) {
        playlists = withLiveProgress(playlists, jobs)
    }

    private fun withLiveProgress(playlists: List<Playlist>, jobs: List<JobUi>): List<Playlist> {
        // playlist name -> (video id -> progress) of the track each job is downloading right now
        val live = jobs.filter { it.activeVideoId != null && it.playlistName != null }
            .groupBy({ it.playlistName!! }, { it.activeVideoId!! to it.progress })
            .mapValues { it.value.toMap() }
        if (live.isEmpty()) return playlists
        return playlists.map { playlist ->
            val progressByVideo = live[playlist.name] ?: return@map playlist
            playlist.copy(tracks = playlist.tracks.map { track ->
                val progress = track.videoId?.let(progressByVideo::get)
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
                youtubePlaylistId = summary.youtubePlaylistId,
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
            fileMissing = downloaded && (filePath.isNullOrEmpty() || !File(filePath).isFile),
            videoId = videoId,
            playlistName = playlistName,
            sourceUrl = sourceUrl,
            youtubePosition = youtubePosition,
            failed = if (failedState) downloadErrorMessage ?: "Ошибка загрузки" else null,
        )
    }
}
