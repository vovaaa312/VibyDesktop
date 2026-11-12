package com.viby.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viby.app.state.AppState
import com.viby.app.state.LibrarySearch
import com.viby.app.state.Screen
import com.viby.app.theme.VibyColors
import com.viby.app.ui.ContextMenuBox
import com.viby.app.ui.CoverArt
import com.viby.app.ui.EmptyHint
import com.viby.app.ui.MenuAction
import com.viby.app.ui.ScrollLazyColumn
import com.viby.app.ui.TrackRow
import com.viby.app.ui.tracksLabel

/** Search across the whole library (Ctrl+F): playlists by name, tracks by title and artist. */
@Composable
fun SearchScreen(state: AppState) {
    val query = state.searchQuery
    val playlists = state.library.playlists
    val result = remember(query, playlists) { LibrarySearch.search(playlists, query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    var selected by remember { mutableStateOf<String?>(null) }
    val player = state.player

    ScrollLazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 64.dp, bottom = 16.dp)) {
                Text("Поиск", style = MaterialTheme.typography.headlineMedium, color = VibyColors.TextPrimary)
                Spacer(Modifier.height(16.dp))
                SearchField(
                    query,
                    { state.searchQuery = it },
                    placeholder = "Трек, исполнитель или плейлист",
                    modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                    focusRequester = focus,
                )
            }
        }
        when {
            query.isBlank() -> item {
                Box(Modifier.fillMaxWidth().height(240.dp)) {
                    EmptyHint("Поиск по всей медиатеке", "Ищет в названиях треков, исполнителях и плейлистах.")
                }
            }
            result.isEmpty -> item {
                Box(Modifier.fillMaxWidth().height(240.dp)) {
                    EmptyHint("Ничего не найдено", "Проверьте написание или попробуйте часть названия.")
                }
            }
        }
        if (result.playlists.isNotEmpty()) {
            item { SectionTitle("Плейлисты") }
            itemsIndexed(result.playlists, key = { _, p -> "playlist:${p.id}" }) { _, playlist ->
                Row(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { state.navigate(Screen.PlaylistScreen(playlist.id)) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverArt(playlist.id, 40.dp, corner = 4.dp, imageUrl = playlist.coverUrl)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(playlist.name, style = MaterialTheme.typography.bodyLarge, color = VibyColors.TextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Плейлист • ${tracksLabel(playlist.tracks.size)}", style = MaterialTheme.typography.bodySmall,
                            color = VibyColors.TextSecondary)
                    }
                }
            }
        }
        if (result.tracks.isNotEmpty()) {
            item { SectionTitle("Треки · ${result.tracks.size}") }
            itemsIndexed(result.tracks, key = { _, hit -> "track:${hit.track.id}" }) { index, hit ->
                val track = hit.track
                ContextMenuBox(
                    header = track.title,
                    actions = {
                        buildList {
                            add(MenuAction("Воспроизвести") { state.playPlaylist(hit.playlist, track) })
                            add(MenuAction("Добавить в очередь") { player.enqueue(listOf(track)) })
                            add(MenuAction("Вставить после проигрываемого") { player.enqueueNext(listOf(track)) })
                            if (track.videoId != null) add(MenuAction("Радио по треку") { state.downloads.radio(track) })
                            val liked = state.favorites.isFavorite(track)
                            add(MenuAction(if (liked) "Убрать из избранного" else "В избранное") { state.favorites.toggle(track) })
                            hit.playlists.forEach { playlist ->
                                add(MenuAction("Открыть «${playlist.name}»") {
                                    state.navigate(Screen.PlaylistScreen(playlist.id))
                                })
                            }
                        }
                    },
                ) {
                    TrackRow(
                        number = index + 1,
                        track = track,
                        isCurrent = player.current?.id == track.id,
                        isPlaying = player.isPlaying,
                        selected = selected == track.id,
                        onClick = { selected = track.id },
                        onPlay = {
                            if (track.failed != null) state.showMessage(track.failed)
                            else state.playPlaylist(hit.playlist, track)
                        },
                        onTogglePlay = player::togglePlay,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        showAdded = false,
                        context = hit.playlists.joinToString(", ") { it.name },
                        favorite = state.favorites.isFavorite(track),
                        onFavorite = { state.favorites.toggle(track) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = VibyColors.TextPrimary,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 8.dp),
    )
}
