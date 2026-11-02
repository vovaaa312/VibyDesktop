package com.viby.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viby.app.model.Playlist
import com.viby.app.model.Track
import com.viby.app.state.AppState
import com.viby.app.state.Screen
import com.viby.app.theme.VibyColors
import com.viby.app.ui.ScrollColumn
import com.viby.app.ui.CoverArt
import com.viby.app.ui.PlayButton
import com.viby.app.ui.SectionTitle
import com.viby.app.ui.coverColors
import com.viby.app.ui.tracksLabel
import java.time.LocalTime

private enum class HomeFilter(val label: String) { ALL("Всё"), PLAYLISTS("Плейлисты"), RECENT("Недавние") }

@Composable
fun HomeScreen(state: AppState) {
    var filter by remember { mutableStateOf(HomeFilter.ALL) }
    val playlists = state.library.playlists
    if (state.library.loaded && playlists.isEmpty()) {
        Welcome(state)
        return
    }
    val accent = playlists.firstOrNull()?.let { coverColors(it.id).first } ?: VibyColors.Accent
    ScrollColumn(
        Modifier.fillMaxSize(),
        contentModifier = Modifier
            .background(Brush.verticalGradient(0f to accent.copy(alpha = 0.35f), 0.35f to Color.Transparent)),
    ) {
        Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 64.dp, bottom = 24.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeFilter.entries.forEach { Chip(it.label, it == filter) { filter = it } }
            }
            Spacer(Modifier.height(24.dp))
            Text(greeting(), style = MaterialTheme.typography.headlineMedium, color = VibyColors.TextPrimary)
            Spacer(Modifier.height(16.dp))
            if (filter != HomeFilter.RECENT) ShortcutGrid(state, playlists.take(8))
        }
        if (filter != HomeFilter.PLAYLISTS) {
            Column(Modifier.padding(horizontal = 32.dp)) {
                SectionTitle("Быстрый выбор")
                QuickPicks(state, state.library.recentTracks(16))
            }
            Spacer(Modifier.height(32.dp))
        }
        if (filter != HomeFilter.RECENT) {
            Column(Modifier.padding(horizontal = 32.dp)) { SectionTitle("Ваши плейлисты") }
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(playlists, key = { it.id }) { PlaylistCard(state, it) }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Welcome(state: AppState) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(greeting(), style = MaterialTheme.typography.headlineMedium, color = VibyColors.TextPrimary)
        Spacer(Modifier.height(12.dp))
        Text(
            "Библиотека пуста. Вставьте ссылку на видео или плейлист YouTube — треки скачаются в MP3 с обложками.",
            style = MaterialTheme.typography.bodyLarge,
            color = VibyColors.TextSecondary,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { state.navigate(Screen.Add) },
            colors = ButtonDefaults.buttonColors(containerColor = VibyColors.Accent, contentColor = Color.Black),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.height(48.dp),
        ) { Text("Добавить с YouTube", style = MaterialTheme.typography.titleMedium) }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Доброе утро"
    in 12..17 -> "Добрый день"
    in 18..22 -> "Добрый вечер"
    else -> "Доброй ночи"
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) VibyColors.TextPrimary else VibyColors.Highlight)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = if (selected) Color.Black else VibyColors.TextPrimary)
    }
}

/** Spotify-style compact cards: cover on the left, play button appears on hover. */
@Composable
private fun ShortcutGrid(state: AppState, playlists: List<Playlist>) {
    BoxWithConstraints {
        val columns = if (maxWidth > 900.dp) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            playlists.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { playlist -> Shortcut(state, playlist, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Shortcut(state: AppState, playlist: Playlist, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val playing = state.player.contextPlaylistId == playlist.id && state.player.isPlaying
    Row(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (hovered) Color(0x33FFFFFF) else Color(0x1AFFFFFF))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) {
                state.navigate(Screen.PlaylistScreen(playlist.id))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(playlist.id, 56.dp, corner = 0.dp, imageUrl = playlist.coverUrl)
        Text(
            playlist.name,
            style = MaterialTheme.typography.titleSmall,
            color = VibyColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        if (hovered || playing) {
            Box(Modifier.padding(end = 8.dp)) {
                PlayButton(playing, {
                    if (playing) state.player.togglePlay() else state.playPlaylist(playlist)
                }, size = 36.dp)
            }
        }
    }
}

/** YouTube Music "Quick picks": a horizontally scrolling grid of four-row track columns. */
@Composable
private fun QuickPicks(state: AppState, tracks: List<Track>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(tracks.chunked(4)) { column ->
            Column(Modifier.width(320.dp)) {
                column.forEach { track ->
                    val interaction = remember { MutableInteractionSource() }
                    val hovered by interaction.collectIsHoveredAsState()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (hovered) VibyColors.Hover else Color.Transparent)
                            .hoverable(interaction)
                            .clickable(interactionSource = interaction, indication = null) {
                                state.player.playQueue(tracks, track, null)
                            }
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverArt(track.id, 48.dp, imageUrl = track.thumbnailUrl)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, style = MaterialTheme.typography.bodyMedium,
                                color = if (state.player.current?.id == track.id) VibyColors.AccentBright
                                else VibyColors.TextPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, style = MaterialTheme.typography.bodySmall,
                                color = VibyColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistCard(state: AppState, playlist: Playlist) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier
            .width(196.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) VibyColors.Elevated else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) {
                state.navigate(Screen.PlaylistScreen(playlist.id))
            }
            .padding(12.dp),
    ) {
        Box {
            CoverArt(playlist.id, 172.dp, corner = 6.dp, imageUrl = playlist.coverUrl)
            if (hovered) {
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                    PlayButton(false, { state.playPlaylist(playlist) }, size = 48.dp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(playlist.name, style = MaterialTheme.typography.titleSmall, color = VibyColors.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Плейлист • ${tracksLabel(playlist.tracks.size)}", style = MaterialTheme.typography.bodySmall,
            color = VibyColors.TextSecondary, maxLines = 1)
    }
}
