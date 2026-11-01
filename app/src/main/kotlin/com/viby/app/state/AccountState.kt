package com.viby.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.viby.core.sync.YoutubePlaylistApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    val isSignedIn: Boolean get() = channel != null

    init {
        if (account.isConfigured) {
            app.scope.launch {
                channel = runCatching {
                    withContext(Dispatchers.IO) { if (account.isSignedIn) account.channel() else null }
                }.getOrNull()
            }
        }
    }

    fun signIn() = task {
        val result = account.signIn { uri -> openInBrowser(uri) }
        app.scope.launch {
            channel = result
            app.showMessage("Вы вошли как ${result.title()}")
        }
    }

    fun signOut() = task {
        account.signOut()
        app.scope.launch {
            channel = null
            playlists = null
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

    private fun task(block: () -> Unit) {
        if (busy) return
        busy = true
        app.scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                app.showMessage(e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
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
