package com.viby.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.sp
import com.viby.app.model.SortMode
import com.viby.app.model.Track
import com.viby.app.state.AppState
import com.viby.app.state.CollectionRef
import com.viby.app.state.CollectionView
import com.viby.app.state.LibrarySearch.TrackHit
import com.viby.app.state.Screen
import com.viby.app.theme.VibyColors
import com.viby.app.ui.ClickKind
import com.viby.app.ui.ContextMenuBox
import com.viby.app.ui.CoverArt
import com.viby.app.ui.EmptyHint
import com.viby.app.ui.FitTitle
import com.viby.app.ui.IconAction
import com.viby.app.ui.MenuAction
import com.viby.app.ui.PlayButton
import com.viby.app.ui.ScrollLazyColumn
import com.viby.app.ui.TrackRow
import com.viby.app.ui.coverColors
import com.viby.app.ui.tracksLabel

/** A library section as a track list: Избранное, Недавно прослушанные, an artist, an album or a folder. */
@Composable
fun CollectionScreen(state: AppState, ref: CollectionRef) {
    val view = state.library.collection(ref)
    if (view == null) {
        EmptyHint("Здесь больше ничего нет", "Треки этого раздела удалены из медиатеки.")
        return
    }
    TrackCollection(state, ref, view) { CollectionHeader(ref, view) }
}

/** Texts for a section that has no tracks yet. */
private fun emptyHint(ref: CollectionRef): Pair<String, String> = when (ref) {
    CollectionRef.Favorites -> "В избранном пока пусто" to
        "Наведите на трек и нажмите на сердечко — он появится здесь, в каком бы плейлисте ни лежал."
    CollectionRef.Recent -> "Вы ещё ничего не слушали" to "Здесь будут последние треки, которые вы включали."
    else -> "Треков нет" to "Добавьте музыку с YouTube или с компьютера."
}

/**
 * Header, play/shuffle/search/sort row and the tracks of [view]. Playing starts a queue of exactly
 * the list on screen (sorted and filtered), with [ref] as its context.
 */
@Composable
internal fun TrackCollection(
    state: AppState,
    ref: CollectionRef,
    view: CollectionView,
    header: @Composable () -> Unit,
) {
    var query by remember(ref) { mutableStateOf("") }
    var sort by remember(ref) { mutableStateOf(SortMode.YOUTUBE) }
    var selection by remember(ref) { mutableStateOf(emptySet<String>()) }
    var anchor by remember(ref) { mutableStateOf<String?>(null) }
    var moveDialog by remember { mutableStateOf<List<Track>?>(null) }
    val player = state.player

    val hits = remember(view.tracks, sort, query) {
        val byId = view.tracks.associateBy { it.track.id }
        state.library.sorted(view.tracks.map { it.track }, sort)
            .filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) }
            .mapNotNull { byId[it.id] }
    }
    val visible = hits.map { it.track }
    val playingHere = player.contextPlaylistId == ref.id

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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        ScrollLazyColumn(Modifier.fillMaxSize()) {
            item { header() }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = if (compact) 16.dp else 32.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp),
                ) {
                    PlayButton(playingHere && player.isPlaying, {
                        if (playingHere) player.togglePlay() else state.playCollection(ref, visible)
                    }, size = if (compact) 48.dp else 56.dp)
                    IconAction(Icons.Rounded.Shuffle, "Перемешать",
                        { state.playCollection(ref, visible, shuffle = true) }, size = 40.dp, iconSize = 28.dp)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        SearchField(query, { query = it }, placeholder = "Поиск в разделе",
                            modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth())
                    }
                    SortMenu(sort, { sort = it }, showLabel = !compact, defaultLabel = defaultOrder(ref))
                }
            }
            if (visible.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(220.dp)) {
                        if (view.tracks.isEmpty()) {
                            val (title, text) = emptyHint(ref)
                            EmptyHint(title, text)
                        } else {
                            EmptyHint("Ничего не найдено", "Попробуйте другой запрос.")
                        }
                    }
                }
            }
            trackRows(state, ref, hits, selection, compact, ::select, { selection = emptySet() }) { moveDialog = it }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    moveDialog?.let { tracks ->
        MoveDialog(playlists = state.library.playlists, onDismiss = { moveDialog = null }) { target ->
            state.sendTracks(tracks, target)
            selection = emptySet()
        }
    }
}

private fun LazyListScope.trackRows(
    state: AppState,
    ref: CollectionRef,
    hits: List<TrackHit>,
    selection: Set<String>,
    compact: Boolean,
    select: (Track, ClickKind) -> Unit,
    clearSelection: () -> Unit,
    send: (List<Track>) -> Unit,
) {
    val player = state.player
    val visible = hits.map { it.track }
    itemsIndexed(hits, key = { _, hit -> hit.track.id }) { index, hit ->
        val track = hit.track
        val targets = if (track.id in selection) visible.filter { it.id in selection } else listOf(track)
        ContextMenuBox(
            header = if (targets.size > 1) "Выбрано: ${tracksLabel(targets.size)}" else track.title,
            actions = {
                buildList {
                    add(MenuAction("Воспроизвести") { state.playCollection(ref, visible, track) })
                    add(MenuAction("Добавить в очередь") { player.enqueue(targets) })
                    add(MenuAction("Вставить после проигрываемого") { player.enqueueNext(targets) })
                    if (track.videoId != null) add(MenuAction("Радио по треку") { state.downloads.radio(track) })
                    val liked = targets.all(state.favorites::isFavorite)
                    add(MenuAction(if (liked) "Убрать из избранного" else "В избранное") {
                        state.favorites.set(targets, !liked)
                        clearSelection()
                    })
                    add(MenuAction("Отправить в плейлист…") { send(targets) })
                    // where the song lives; the list would be endless for a song in many playlists
                    hit.playlists.take(3).forEach { playlist ->
                        add(MenuAction("Открыть «${playlist.name}»") { state.navigate(Screen.PlaylistScreen(playlist.id)) })
                    }
                }
            },
        ) {
            TrackRow(
                number = index + 1,
                track = track,
                // a song is one row here, whichever of its copies is in the queue
                isCurrent = player.current?.key == track.key,
                isPlaying = player.isPlaying,
                selected = track.id in selection,
                onClick = { select(track, it) },
                onPlay = {
                    if (track.failed != null) state.showMessage(track.failed)
                    else state.playCollection(ref, visible, track)
                },
                onTogglePlay = player::togglePlay,
                modifier = Modifier.padding(horizontal = if (compact) 4.dp else 16.dp),
                showAdded = false,
                context = hit.playlists.joinToString(", ") { it.name },
                favorite = state.favorites.isFavorite(track),
                onFavorite = { state.favorites.toggle(track) },
            )
        }
    }
}

/** What "no sorting" means for each section, shown in the sort menu. */
private fun defaultOrder(ref: CollectionRef): String = when (ref) {
    CollectionRef.Favorites -> "Сначала новые в избранном"
    CollectionRef.Recent -> "Сначала недавние"
    else -> "Порядок медиатеки"
}

@Composable
private fun CollectionHeader(ref: CollectionRef, view: CollectionView) {
    val cover = view.tracks.firstOrNull { it.track.thumbnailUrl != null }?.track ?: view.tracks.firstOrNull()?.track
    val (top, _) = coverColors(ref.id)
    BoxWithConstraints {
        val compact = maxWidth < 600.dp
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(top.copy(alpha = 0.75f), top.copy(alpha = 0.25f))))
                .padding(start = if (compact) 16.dp else 32.dp, end = if (compact) 16.dp else 32.dp,
                    top = 56.dp, bottom = if (compact) 16.dp else 24.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            val size = if (compact) 120.dp else 200.dp
            if (ref == CollectionRef.Favorites) {
                Box(
                    Modifier.size(size).clip(RoundedCornerShape(6.dp))
                        .background(Brush.linearGradient(listOf(VibyColors.Accent, VibyColors.AccentBright))),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(size * 0.45f)) }
            } else {
                CoverArt(cover?.id ?: ref.id, size, corner = 6.dp, imageUrl = cover?.thumbnailUrl)
            }
            Spacer(Modifier.width(if (compact) 16.dp else 24.dp))
            Column {
                Text(view.kind, style = MaterialTheme.typography.bodySmall, color = VibyColors.TextPrimary)
                FitTitle(view.title, style = MaterialTheme.typography.displaySmall, maxSize = 56.sp, minSize = 24.sp,
                    modifier = Modifier.padding(vertical = 8.dp))
                view.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = VibyColors.TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "${tracksLabel(view.tracks.size)}, ${totalDuration(view.tracks.sumOf { it.track.durationMs })}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VibyColors.TextSecondary,
                )
            }
        }
    }
}

/** A row of the artist / album / folder lists. */
@Composable
internal fun GroupRow(seed: String, cover: Track?, name: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(cover?.id ?: seed, 48.dp, corner = 4.dp, imageUrl = cover?.thumbnailUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = VibyColors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = VibyColors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
