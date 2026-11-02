package com.viby.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.viby.core.sync.YoutubePlaylistApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

/** Google account (YouTube Data API) state for the UI. */
class AccountState(private val app: AppState) {
    private val account get() = app.engine.account()

    val isConfigured: Boolean get() = account.isConfigured

    var channel by mutableStateOf<YoutubePlaylistApi.Channel?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var playlists by mutableStateOf<List<YoutubePlaylistApi.AccountPlaylist>?>(null)
        private set

    /**
     * Based on the stored refresh token, not on the channel request: a failed request at start-up
     * (no network yet) must not silently turn YouTube sync off for the whole session.
     */
    var isSignedIn by mutableStateOf(account.isConfigured && account.isSignedIn)
        private set

    /** Serializes account work so a second action waits instead of being dropped. */
    private val queue = Mutex()
    private var running by mutableStateOf(0)

    init {
        if (isSignedIn) loadChannel()
    }

    private fun loadChannel() {
        app.scope.launch {
            runCatching { withContext(Dispatchers.IO) { account.channel() } }
                .onSuccess { channel = it }
                .onFailure { app.showMessage("Не удалось получить данные канала YouTube: ${it.message}") }
        }
    }

    fun signIn() = task {
        val result = account.signIn { uri -> openInBrowser(uri) }
        app.scope.launch {
            channel = result
            isSignedIn = true
            app.showMessage("Вы вошли как ${result.title()}")
        }
    }

    fun signOut() = task {
        account.signOut()
        app.scope.launch {
            channel = null
            playlists = null
            isSignedIn = false
        }
    }

    fun loadPlaylists() = task {
        val result = account.playlists()
        app.scope.launch { playlists = result }
    }

    fun download(playlist: YoutubePlaylistApi.AccountPlaylist) = task {
        account.download(playlist, playlist.title())
        app.scope.launch { app.showMessage("«${playlist.title()}» добавлен в загрузки") }
    }

    fun saveToYoutube(playlistName: String) = task {
        account.saveToYoutube(playlistName, null)
        app.scope.launch { app.showMessage("«$playlistName» сохранён на YouTube как приватный плейлист") }
    }

    /** Android addTrack: [onDone] learns whether the video still had to be downloaded. */
    fun addTrackAtStart(playlistName: String, url: String, onDone: (queued: Boolean) -> Unit) = task {
        val result = account.addTrackAtStart(playlistName, url)
        app.scope.launch {
            app.showMessage("Добавлено в Viby и YouTube")
            if (result.downloadRequired) app.downloads.enqueue(url, playlistName) { onDone(true) } else onDone(false)
        }
    }

    /** Android syncCopiedTracks: sends the copied videos to the target's YouTube playlist. */
    fun copyToYoutube(playlistName: String, videoIds: List<String>) = task {
        val result = account.copyToYoutube(playlistName, videoIds)
        app.scope.launch {
            app.showMessage(
                if (result.failedCount == 0) "Добавлено на YouTube: ${result.addedCount}"
                else "Добавлено: ${result.addedCount} · ошибок: ${result.failedCount} (${result.firstError})"
            )
        }
    }

    fun deleteSynced(playlistName: String, trackIds: Set<String>, deleteFiles: Boolean) = task {
        val result = account.deleteSynced(playlistName, trackIds.map(String::toLong), deleteFiles)
        app.scope.launch {
            app.showMessage(
                if (result.failedCount == 0) "Удалено: ${result.removed.size}"
                else "Удалено: ${result.removed.size} · ошибок: ${result.failedCount} (${result.firstError})"
            )
        }
    }

    private fun task(block: () -> Unit) {
        running++
        busy = true
        app.scope.launch {
            try {
                queue.withLock { withContext(Dispatchers.IO) { block() } }
            } catch (e: Exception) {
                app.showMessage(e.message ?: e.javaClass.simpleName)
            } finally {
                running--
                busy = running > 0
            }
        }
    }

    private fun openInBrowser(uri: URI) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(uri)
        } else {
            error("Откройте в браузере: $uri")
        }
    }
}
