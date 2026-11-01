package com.viby.app.ui.screens

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viby.app.model.Playlist
import com.viby.app.model.SortMode
import com.viby.app.model.Track
import com.viby.app.state.AppState
import com.viby.app.ui.ClickKind
import com.viby.app.ui.CoverArt
import com.viby.app.ui.EmptyHint
import com.viby.app.ui.IconAction
import com.viby.app.ui.PlayButton
import com.viby.app.ui.TrackRow
import com.viby.app.ui.coverColors
import com.viby.app.ui.tracksLabel
import com.viby.app.theme.VibyColors
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun PlaylistScreen(state: AppState, playlistId: String) {
    val playlist = state.library.playlist(playlistId) ?: run {
        EmptyHint("Плейлист не найден", "Возможно, он был удалён.")
        return
    }
    var query by remember(playlistId) { mutableStateOf("") }
    var sort by remember(playlistId) { mutableStateOf(SortMode.YOUTUBE) }
    var selection by remember(playlistId) { mutableStateOf(emptySet<String>()) }
    var anchor by remember(playlistId) { mutableStateOf<String?>(null) }
    var moveDialog by remember { mutableStateOf<Set<String>?>(null) }

    val visible = state.library.sorted(playlist.tracks, sort).filter {
        query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true)
    }
    val player = state.player
    val playingHere = player.contextPlaylistId == playlist.id

    fun select(track: Track, kind: ClickKind) {
        selection = when (kind) {
            ClickKind.PLAIN -> setOf(track.id)
            ClickKind.TOGGLE -> if (track.id in selection) selection - track.id else selection + track.id
            ClickKind.RANGE -> {
                val from = visible.indexOfFirst { it.id == anchor }.takeIf { it >= 0 } ?: 0
                val to = visible.indexOf(track)
                visible.subList(minOf(from, to), maxOf(from, to) + 1).map { it.id }.toSet()
            }
        }
        if (kind != ClickKind.RANGE) anchor = track.id
    }

    fun actionTargets(track: Track): List<Track> =
        if (track.id in selection) visible.filter { it.id in selection } else listOf(track)

    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState()) {
        item { Header(playlist) }
        item {
            ActionRow(
                playing = playingHere && player.isPlaying,
                onPlay = { if (playingHere) player.togglePlay() else state.playPlaylist(playlist) },
                onShuffle = { state.playPlaylist(playlist, shuffle = true) },
                onSync = {
                    val source = playlist.sourceUrl
                    if (source == null) {
                        state.showMessage("Этот плейлист не связан с плейлистом YouTube")
                    } else {
                        // re-running the job only downloads tracks that are missing locally
                        state.downloads.enqueue(source, playlist.name) {
                            state.showMessage("Проверяю новые треки в плейлисте YouTube")
                        }
                    }
                },
                query = query,
                onQuery = { query = it },
                sort = sort,
                onSort = { sort = it },
            )
        }
        item { ColumnsHeader() }
        if (visible.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(200.dp)) {
                    if (playlist.tracks.isEmpty()) {
                        EmptyHint("Плейлист пуст", "Добавьте сюда треки по ссылке с YouTube.")
                    } else {
                        EmptyHint("Ничего не найдено", "Попробуйте другой запрос.")
                    }
                }
            }
        }
        itemsIndexed(visible, key = { _, track -> track.id }) { index, track ->
            ContextMenuArea(items = {
                val targets = actionTargets(track)
                val label = if (targets.size > 1) " (${targets.size})" else ""
                buildList {
                    add(ContextMenuItem("Играть") { state.playPlaylist(playlist.copy(tracks = visible), track) })
                    add(ContextMenuItem("Играть следующим$label") { player.enqueueNext(targets) })
                    add(ContextMenuItem("Добавить в очередь$label") { player.enqueue(targets) })
                    add(ContextMenuItem("Переместить в плейлист…$label") { moveDialog = targets.map { it.id }.toSet() })
                    if (track.videoId != null) {
                        add(ContextMenuItem("Радио по треку") { state.downloads.radio(track) })
                    }
                    val retryable = targets.filter { !it.isDownloaded && it.videoId != null }
                    if (retryable.isNotEmpty()) {
                        add(ContextMenuItem("Повторить загрузку${if (retryable.size > 1) " (${retryable.size})" else ""}") {
                            retryable.forEach { state.downloads.retry(it, playlist.name) }
                        })
                    }
                    add(ContextMenuItem("Удалить из плейлиста$label") {
                        state.library.removeTracks(targets.map { it.id }.toSet())
                        selection = emptySet()
                    })
                }
            }) {
                TrackRow(
                    number = index + 1,
                    track = track,
                    isCurrent = playingHere && player.current?.id == track.id,
                    isPlaying = player.isPlaying,
                    selected = track.id in selection,
                    onClick = { select(track, it) },
                    onPlay = {
                        if (track.failed != null) {
                            state.showMessage(track.failed)
                        } else {
                            state.playPlaylist(playlist.copy(tracks = visible), track)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }

    moveDialog?.let { ids ->
        MoveDialog(
            playlists = state.library.playlists.filter { it.id != playlist.id },
            onDismiss = { moveDialog = null },
        ) { target ->
            state.library.moveTracks(ids, target.id)
            selection = emptySet()
            state.showMessage("${tracksLabel(ids.size)} → «${target.name}»")
        }
    }
}

@Composable
private fun Header(playlist: Playlist) {
    val (top, _) = coverColors(playlist.id)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(top.copy(alpha = 0.75f), top.copy(alpha = 0.25f))))
            .padding(start = 32.dp, end = 32.dp, top = 56.dp, bottom = 24.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        CoverArt(playlist.id, 200.dp, corner = 6.dp, imageUrl = playlist.coverUrl)
        Spacer(Modifier.width(24.dp))
        Column {
            Text("Плейлист", style = MaterialTheme.typography.bodySmall, color = VibyColors.TextPrimary)
            Text(
                playlist.name,
                style = MaterialTheme.typography.displaySmall,
                color = VibyColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playlist.youtubeSynced) {
                    Icon(Icons.Rounded.Sync, null, tint = VibyColors.TextPrimary, modifier = Modifier.size(16.dp))
                    Text(" Синхронизирован с YouTube • ", style = MaterialTheme.typography.bodyMedium,
                        color = VibyColors.TextPrimary)
                }
                Text(
                    "${tracksLabel(playlist.tracks.size)}, ${totalDuration(playlist.durationMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VibyColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    playing: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSync: () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    sort: SortMode,
    onSort: (SortMode) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PlayButton(playing, onPlay)
        IconAction(Icons.Rounded.Shuffle, "Перемешать", onShuffle, size = 40.dp, iconSize = 28.dp)
        IconAction(Icons.Rounded.Sync, "Обновить из YouTube", onSync, size = 40.dp, iconSize = 26.dp)
        Spacer(Modifier.weight(1f))
        SearchField(query, onQuery)
        SortMenu(sort, onSort)
    }
}

@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String = "Поиск в плейлисте") {
    Row(
        Modifier
            .width(240.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(VibyColors.Highlight)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = VibyColors.TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = VibyColors.TextMuted)
            }
            BasicTextField(
                query,
                onQuery,
                singleLine = true,
                textStyle = TextStyle(color = VibyColors.TextPrimary, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                cursorBrush = SolidColor(VibyColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SortMenu(sort: SortMode, onSort: (SortMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(4.dp)).clickable { open = true }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(sort.label, style = MaterialTheme.typography.bodyMedium, color = VibyColors.TextSecondary)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Rounded.Sort, null, tint = VibyColors.TextSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }, containerColor = VibyColors.Highlight) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(mode.label, color = if (mode == sort) VibyColors.AccentBright else VibyColors.TextPrimary)
                    },
                    onClick = { onSort(mode); open = false },
                )
            }
        }
    }
}

@Composable
private fun ColumnsHeader() {
    Column(Modifier.padding(horizontal = 32.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            HeaderText("#", Modifier.width(40.dp))
            HeaderText("Название", Modifier.weight(1f).padding(start = 52.dp))
            HeaderText("Добавлен", Modifier.width(140.dp).padding(start = 16.dp))
            Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Rounded.AccessTime, "Длительность", tint = VibyColors.TextSecondary,
                    modifier = Modifier.size(16.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(VibyColors.Divider))
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HeaderText(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = VibyColors.TextSecondary, modifier = modifier)
}

@Composable
private fun MoveDialog(playlists: List<Playlist>, onDismiss: () -> Unit, onMove: (Playlist) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VibyColors.Elevated,
        title = { Text("Переместить в плейлист") },
        text = {
            LazyColumn(Modifier.height(320.dp)) {
                itemsIndexed(playlists) { _, target ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .clickable { onMove(target); onDismiss() }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverArt(target.id, 40.dp, imageUrl = target.coverUrl)
                        Spacer(Modifier.width(12.dp))
                        Text(target.name, color = VibyColors.TextPrimary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun totalDuration(ms: Long): String {
    val duration = ms.milliseconds
    val hours = duration.inWholeHours
    val minutes = duration.inWholeMinutes % 60
    return if (hours > 0) "$hours ч $minutes мин" else "$minutes мин"
}
