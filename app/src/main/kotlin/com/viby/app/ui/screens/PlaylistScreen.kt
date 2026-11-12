package com.viby.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.viby.app.ui.FitTitle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import com.viby.app.model.Playlist
import com.viby.app.model.PlaylistView
import com.viby.app.model.SortMode
import com.viby.app.model.Track
import com.viby.app.model.TrackFilter
import com.viby.app.state.AppState
import com.viby.app.ui.ClickKind
import com.viby.app.ui.ContextMenuBox
import com.viby.app.ui.CoverArt
import com.viby.app.ui.MenuAction
import com.viby.app.ui.ScrollLazyColumn
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
    var view by remember(playlistId) { mutableStateOf(state.library.view(playlistId)) }
    var selection by remember(playlistId) { mutableStateOf(emptySet<String>()) }
    var anchor by remember(playlistId) { mutableStateOf<String?>(null) }
    var moveDialog by remember { mutableStateOf<Set<String>?>(null) }

    val visible = state.library.visible(playlist.tracks, view).filter {
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

    val listState = remember(playlistId) { LazyListState() }
    val onPlay = { if (playingHere) player.togglePlay() else state.playPlaylist(playlist) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalCompactPlaylist provides (maxWidth < 600.dp)) {
    ScrollLazyColumn(Modifier.fillMaxSize(), state = listState) {
        item(key = HEADER_KEY) {
            Header(playlist)
        }
        item {
            ActionRow(
                playing = playingHere && player.isPlaying,
                onPlay = onPlay,
                onShuffle = { state.playPlaylist(playlist, shuffle = true) },
                onImport = { state.library.importFiles(playlist.name) },
                onSaveRadio = if (state.account.isSignedIn && playlist.isUnsavedRadio) {
                    { state.account.offerSaveRadio(playlist.name) }
                } else null,
                onSync = {
                    val source = playlist.sourceUrl
                    if (playlist.canSync && state.account.isSignedIn) {
                        // private playlists (saved radios) are only readable through the account
                        state.account.refreshLinked(playlist.name)
                    } else if (source == null) {
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
                sort = view.sort,
                onSort = { view = view.copy(sort = it); state.library.saveView(playlistId, view) },
            )
        }
        val counts = TrackFilter.entries.associateWith { f -> playlist.tracks.count(f::matches) }
        if (counts.any { (filter, count) -> filter != TrackFilter.ALL && count > 0 } || view.filter != TrackFilter.ALL) {
            item {
                FilterChips(view.filter, counts) { view = view.copy(filter = it); state.library.saveView(playlistId, view) }
            }
        }
        item(key = COLUMNS_KEY) { ColumnsHeader() }
        if (visible.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(200.dp)) {
                    if (playlist.tracks.isEmpty()) {
                        EmptyHint("Плейлист пуст", "Добавьте сюда треки по ссылке с YouTube.")
                    } else if (query.isBlank() && view.filter != TrackFilter.ALL) {
                        EmptyHint(
                            when (view.filter) {
                                TrackFilter.FAILED -> "Ошибок загрузки нет"
                                TrackFilter.MISSING -> "Все файлы на месте"
                                else -> "Все треки загружены"
                            },
                            "Сейчас показаны только треки «${view.filter.label.lowercase()}».",
                        )
                    } else {
                        EmptyHint("Ничего не найдено", "Попробуйте другой запрос.")
                    }
                }
            }
        }
        itemsIndexed(visible, key = { _, track -> track.id }) { index, track ->
            val targets = actionTargets(track)
            ContextMenuBox(
                header = if (targets.size > 1) "Выбрано: ${tracksLabel(targets.size)}" else track.title,
                actions = {
                    val ids = targets.map { it.id }.toSet()
                    buildList {
                        add(MenuAction("Воспроизвести") { state.playPlaylist(playlist, track) })
                        add(MenuAction("Добавить в очередь") { player.enqueue(targets) })
                        add(MenuAction("Вставить после проигрываемого") { player.enqueueNext(targets) })
                        if (track.videoId != null) add(MenuAction("Радио по треку") { state.downloads.radio(track) })
                        add(MenuAction("Отправить в плейлист…") { moveDialog = ids })
                        val liked = targets.all(state.favorites::isFavorite)
                        add(MenuAction(if (liked) "Убрать из избранного" else "В избранное") {
                            state.favorites.set(targets, !liked)
                        })
                        if (track.id !in selection) add(MenuAction("Выделить") { selection = selection + track.id })
                        val retryable = targets.filter { !it.isDownloaded && it.videoId != null }
                        if (retryable.isNotEmpty()) {
                            add(MenuAction("Повторить загрузку") {
                                retryable.forEach { state.downloads.retry(it) }
                            })
                        }
                        val lost = targets.filter { it.fileMissing && (it.videoId != null || it.sourceUrl != null) }
                        if (lost.isNotEmpty()) {
                            add(MenuAction("Скачать заново") { state.storage.redownload(lost) })
                        }
                        add(MenuAction("Удалить из плейлиста") {
                            state.deleteTracks(playlist, targets, deleteFiles = false)
                            selection = emptySet()
                        })
                        add(MenuAction("Удалить с устройства", danger = true) {
                            state.deleteTracks(playlist, targets, deleteFiles = true)
                            selection = emptySet()
                        })
                    }
                },
            ) {
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
                            state.playPlaylist(playlist, track)
                        }
                    },
                    onTogglePlay = player::togglePlay,
                    modifier = Modifier.padding(horizontal = if (LocalCompactPlaylist.current) 4.dp else 16.dp),
                    showAdded = !LocalCompactPlaylist.current,
                    favorite = state.favorites.isFavorite(track),
                    onFavorite = { state.favorites.toggle(track) },
                )
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
    StickyBar(playlist, listState, playingHere && player.isPlaying, onPlay)
    }
    }

    moveDialog?.let { ids ->
        MoveDialog(
            playlists = state.library.playlists.filter { it.id != playlist.id },
            onDismiss = { moveDialog = null },
        ) { target ->
            state.sendTracks(playlist.tracks.filter { it.id in ids }, target)
            selection = emptySet()
        }
    }
}

@Composable
private fun Header(playlist: Playlist) {
    val (top, _) = coverColors(playlist.id)
    val compact = LocalCompactPlaylist.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(top.copy(alpha = 0.75f), top.copy(alpha = 0.25f))))
            .padding(start = if (compact) 16.dp else 32.dp, end = if (compact) 16.dp else 32.dp,
                top = 56.dp, bottom = if (compact) 16.dp else 24.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        CoverArt(playlist.id, if (compact) 120.dp else 200.dp, corner = 6.dp, imageUrl = playlist.coverUrl)
        Spacer(Modifier.width(if (compact) 16.dp else 24.dp))
        Column {
            Text("Плейлист", style = MaterialTheme.typography.bodySmall, color = VibyColors.TextPrimary)
            FitTitle(
                playlist.name,
                style = MaterialTheme.typography.displaySmall,
                maxSize = 56.sp,
                minSize = 24.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Text(
                "${tracksLabel(playlist.tracks.size)}, ${totalDuration(playlist.durationMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = VibyColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun ActionRow(
    playing: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSync: () -> Unit,
    onImport: () -> Unit,
    /** Android "Сохранить радио на YouTube"; null hides the button. */
    onSaveRadio: (() -> Unit)?,
    query: String,
    onQuery: (String) -> Unit,
    sort: SortMode,
    onSort: (SortMode) -> Unit,
) {
    val compact = LocalCompactPlaylist.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (compact) 16.dp else 32.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp),
    ) {
        PlayButton(playing, onPlay, size = if (compact) 48.dp else 56.dp)
        IconAction(Icons.Rounded.Shuffle, "Перемешать", onShuffle, size = 40.dp, iconSize = 28.dp)
        IconAction(Icons.Rounded.Sync, "Обновить из YouTube", onSync, size = 40.dp, iconSize = 26.dp)
        IconAction(Icons.Rounded.AudioFile, "Добавить файлы с компьютера", onImport, size = 40.dp, iconSize = 24.dp)
        onSaveRadio?.let { SaveRadioButton(showLabel = !compact, onClick = it) }
        // the search box shrinks before anything else when the column gets narrow
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            SearchField(query, onQuery, modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth())
        }
        SortMenu(sort, onSort, showLabel = !compact)
    }
}

/** Pill button so saving a radio is as easy to find as on Android, icon only when narrow. */
@Composable
private fun SaveRadioButton(showLabel: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, VibyColors.TextMuted, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CloudUpload, "Сохранить радио на YouTube", tint = VibyColors.TextPrimary,
            modifier = Modifier.size(18.dp))
        if (showLabel) {
            Spacer(Modifier.width(8.dp))
            Text("Сохранить на YouTube", style = MaterialTheme.typography.bodyMedium, color = VibyColors.TextPrimary,
                maxLines = 1)
        }
    }
}

@Composable
fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String = "Поиск в плейлисте",
    modifier: Modifier = Modifier.width(240.dp),
    focusRequester: FocusRequester? = null,
) {
    Row(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(VibyColors.Highlight)
            .padding(start = 10.dp, end = 4.dp),
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
                modifier = Modifier.fillMaxWidth().let { m -> focusRequester?.let { m.focusRequester(it) } ?: m },
            )
        }
        if (query.isNotEmpty()) {
            IconAction(Icons.Rounded.Close, "Очистить поиск", { onQuery("") }, size = 28.dp, iconSize = 16.dp)
        } else {
            Spacer(Modifier.width(28.dp))
        }
    }
}

@Composable
internal fun SortMenu(
    sort: SortMode,
    onSort: (SortMode) -> Unit,
    showLabel: Boolean = true,
    /** Name of [SortMode.YOUTUBE] outside playlists ("Сначала недавние"). */
    defaultLabel: String? = null,
) {
    fun label(mode: SortMode) = if (mode == SortMode.YOUTUBE && defaultLabel != null) defaultLabel else mode.label
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(4.dp)).clickable { open = true }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showLabel) {
                Text(label(sort), style = MaterialTheme.typography.bodyMedium, color = VibyColors.TextSecondary,
                    maxLines = 1)
                Spacer(Modifier.width(6.dp))
            }
            Icon(Icons.AutoMirrored.Rounded.Sort, null, tint = VibyColors.TextSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }, containerColor = VibyColors.Highlight) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(label(mode), color = if (mode == sort) VibyColors.AccentBright else VibyColors.TextPrimary)
                    },
                    onClick = { onSort(mode); open = false },
                )
            }
        }
    }
}

/** "Все · Не загружено · С ошибкой": shown only while the playlist has tracks that are not downloaded. */
@Composable
private fun FilterChips(filter: TrackFilter, counts: Map<TrackFilter, Int>, onFilter: (TrackFilter) -> Unit) {
    val compact = LocalCompactPlaylist.current
    Row(
        Modifier.fillMaxWidth().padding(start = if (compact) 16.dp else 32.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TrackFilter.entries.forEach { option ->
            val count = counts[option] ?: 0
            if (option != TrackFilter.ALL && count == 0 && option != filter) return@forEach
            val selected = option == filter
            Row(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) VibyColors.Accent else VibyColors.Highlight)
                    .clickable { onFilter(option) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(option.label, style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) VibyColors.OnAccent else VibyColors.TextPrimary)
                Spacer(Modifier.width(6.dp))
                Text(count.toString(), style = MaterialTheme.typography.bodySmall,
                    color = if (selected) VibyColors.OnAccent.copy(alpha = 0.8f) else VibyColors.TextMuted)
            }
        }
    }
}

@Composable
private fun ColumnsHeader() {
    val compact = LocalCompactPlaylist.current
    Column(Modifier.padding(horizontal = if (compact) 4.dp else 32.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            HeaderText("#", Modifier.width(40.dp))
            HeaderText("Название", Modifier.weight(1f).padding(start = 52.dp))
            if (!compact) HeaderText("Добавлен", Modifier.width(140.dp).padding(start = 16.dp))
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
internal fun MoveDialog(playlists: List<Playlist>, onDismiss: () -> Unit, onMove: (Playlist) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VibyColors.Elevated,
        title = { Text("Отправить в плейлист") },
        text = {
            ScrollLazyColumn(Modifier.height(320.dp)) {
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

/** True when the playlist column is narrow (small window or side panel open). */
private val LocalCompactPlaylist = compositionLocalOf { false }

internal fun totalDuration(ms: Long): String {
    val duration = ms.milliseconds
    val hours = duration.inWholeHours
    val minutes = duration.inWholeMinutes % 60
    return if (hours > 0) "$hours ч $minutes мин" else "$minutes мин"
}

/**
 * Spotify-style bar that takes over once the big header has scrolled away: play button and
 * playlist name, plus the column titles once the list reaches them.
 */
@Composable
private fun StickyBar(
    playlist: Playlist,
    listState: LazyListState,
    playing: Boolean,
    onPlay: () -> Unit,
) {
    val density = LocalDensity.current
    val barPx = with(density) { STICKY_BAR_HEIGHT.toPx() }
    val headerGone by remember(listState) {
        derivedStateOf {
            val items = listState.layoutInfo.visibleItemsInfo
            val header = items.firstOrNull { it.key == HEADER_KEY }
            // nothing laid out yet means the list is at its top
            if (header == null) items.isNotEmpty() else header.offset + header.size < barPx
        }
    }
    val columnsStuck by remember(listState) {
        derivedStateOf {
            val columns = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == COLUMNS_KEY }
            if (columns == null) listState.firstVisibleItemIndex > 0 else columns.offset < barPx
        }
    }
    val alpha by animateFloatAsState(if (headerGone) 1f else 0f, tween(180))
    if (alpha == 0f) return
    val (top, _) = coverColors(playlist.id)
    Column(Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha }) {
        Row(
            Modifier.fillMaxWidth().height(STICKY_BAR_HEIGHT)
                .background(lerp(VibyColors.Panel, top, 0.55f))
                // leaves room for the back button drawn over the content
                .padding(start = 60.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayButton(playing, onPlay, size = 40.dp)
            Spacer(Modifier.width(16.dp))
            Text(playlist.name, style = MaterialTheme.typography.titleLarge, color = VibyColors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (columnsStuck) {
            Box(Modifier.fillMaxWidth().background(VibyColors.Panel)) { ColumnsHeader() }
        }
    }
}

private val STICKY_BAR_HEIGHT = 64.dp
private const val HEADER_KEY = "header"
private const val COLUMNS_KEY = "columns"
