package com.viby.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viby.app.state.AppState
import com.viby.app.state.Screen
import com.viby.app.theme.VibyColors
import com.viby.app.ui.CoverArt
import com.viby.app.ui.EmptyHint
import com.viby.app.ui.tracksLabel

/** Playlists of the signed-in account (Android YoutubePlaylistsActivity). */
@Composable
fun YoutubePlaylistsScreen(state: AppState) {
    val account = state.account
    LaunchedEffect(account.isSignedIn) {
        if (account.isSignedIn && account.playlists == null) account.loadPlaylists()
    }
    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 64.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Мои плейлисты YouTube", style = MaterialTheme.typography.headlineMedium,
                color = VibyColors.TextPrimary, modifier = Modifier.weight(1f))
            if (account.isSignedIn) {
                OutlinedButton(enabled = !account.busy, onClick = account::loadPlaylists) { Text("Обновить") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Список берётся через YouTube API, поэтому приватные плейлисты скачиваются без кук браузера.",
            style = MaterialTheme.typography.bodyMedium,
            color = VibyColors.TextSecondary,
        )
        Spacer(Modifier.height(16.dp))
        if (account.busy) {
            LinearProgressIndicator(color = VibyColors.Accent, trackColor = VibyColors.Highlight,
                modifier = Modifier.fillMaxWidth().height(3.dp))
            Spacer(Modifier.height(8.dp))
        }
        when {
            !account.isConfigured -> EmptyHint("Вход через Google не настроен",
                "В этой сборке нет OAuth-клиента (client_secret*.json).")
            !account.isSignedIn -> Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp)) {
                Text("Войдите в аккаунт Google, чтобы видеть свои плейлисты.",
                    color = VibyColors.TextSecondary)
                Spacer(Modifier.height(16.dp))
                Button(
                    enabled = !account.busy,
                    onClick = account::signIn,
                    colors = ButtonDefaults.buttonColors(containerColor = VibyColors.Accent, contentColor = Color.Black),
                ) { Text("Войти через Google") }
            }
            account.playlists?.isEmpty() == true -> EmptyHint("Плейлистов нет", "На канале пока нет своих плейлистов.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(account.playlists.orEmpty(), key = { it.id() }) { playlist ->
                    val local = state.library.playlist(playlist.title())
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(VibyColors.Elevated)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverArt(playlist.id(), 56.dp, imageUrl = playlist.thumbnailUrl())
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(playlist.title(), style = MaterialTheme.typography.titleSmall,
                                color = VibyColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${privacyLabel(playlist.privacy())} • ${tracksLabel(playlist.itemCount())}",
                                style = MaterialTheme.typography.bodySmall,
                                color = VibyColors.TextSecondary,
                            )
                        }
                        if (local != null) {
                            OutlinedButton(onClick = { state.navigate(Screen.PlaylistScreen(local.id)) }) {
                                Text("Открыть")
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Button(
                            enabled = !account.busy && state.engine.isReady,
                            onClick = { account.download(playlist) },
                            colors = ButtonDefaults.buttonColors(containerColor = VibyColors.Accent,
                                contentColor = Color.Black),
                        ) { Text(if (local != null) "Докачать" else "Скачать") }
                    }
                }
            }
        }
    }
}

private fun privacyLabel(privacy: String) = when (privacy) {
    "private" -> "Приватный"
    "unlisted" -> "По ссылке"
    "public" -> "Открытый"
    else -> "Плейлист"
}
