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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.viby.app.state.AppState
import com.viby.app.state.CollectionRef
import com.viby.app.state.CollectionView
import com.viby.app.state.LibraryIndex
import com.viby.app.state.LibraryTab
import com.viby.app.state.Screen
import com.viby.app.theme.VibyColors
import com.viby.app.ui.EmptyHint
import com.viby.app.ui.ScrollLazyColumn
import com.viby.app.ui.tracksLabel

/**
 * Медиатека (guideline stage 4): the same music as the playlists, looked at as all tracks,
 * artists, albums, folders on disk and the listening history.
 */
@Composable
fun LibraryScreen(state: AppState) {
    val tab = state.libraryTab
    val index = state.library.index
    val header: @Composable () -> Unit = { LibraryHeader(state, tab) }
    when (tab) {
        LibraryTab.TRACKS -> TrackCollection(
            state, CollectionRef.AllTracks,
            state.library.collection(CollectionRef.AllTracks) ?: CollectionView("", "", null, emptyList()), header,
        )
        LibraryTab.RECENT -> TrackCollection(
            state, CollectionRef.Recent,
            state.library.collection(CollectionRef.Recent) ?: CollectionView("", "", null, emptyList()), header,
        )
        LibraryTab.ARTISTS -> Groups(
            state, header, index.artists, "Поиск исполнителя",
            "Исполнителей пока нет" to "Они появятся, когда в медиатеке будут треки.",
        ) { CollectionRef.Artist(it.key) }
        LibraryTab.ALBUMS -> Groups(
            state, header, index.albums, "Поиск альбома",
            "Альбомов пока нет" to
                "Альбом берётся из тегов файла: он есть у треков из YouTube Music и у файлов, добавленных с компьютера.",
            note = state.albums.progress,
        ) { CollectionRef.Album(it.key) }
        LibraryTab.FOLDERS -> Groups(
            state, header, index.folders, "Поиск папки",
            "Папок пока нет" to "Здесь будут папки на диске, в которых лежат скачанные треки.",
        ) { CollectionRef.Folder(it.key) }
    }
}

@Composable
private fun LibraryHeader(state: AppState, tab: LibraryTab) {
    Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 64.dp)) {
        Text("Медиатека", style = MaterialTheme.typography.headlineMedium, color = VibyColors.TextPrimary)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryTab.entries.forEach { item ->
                val selected = item == tab
                Box(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) VibyColors.Accent else VibyColors.Highlight)
                        .clickable { state.libraryTab = item }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(item.label, style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) VibyColors.OnAccent else VibyColors.TextPrimary, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Groups(
    state: AppState,
    header: @Composable () -> Unit,
    groups: List<LibraryIndex.Group>,
    placeholder: String,
    empty: Pair<String, String>,
    note: String? = null,
    ref: (LibraryIndex.Group) -> CollectionRef,
) {
    var query by remember(placeholder) { mutableStateOf("") }
    val visible = groups.filter {
        query.isBlank() || it.name.contains(query, true) || it.detail?.contains(query, true) == true
    }
    ScrollLazyColumn(Modifier.fillMaxSize()) {
        item { header() }
        item {
            Column(Modifier.padding(horizontal = 32.dp, vertical = 20.dp)) {
                SearchField(query, { query = it }, placeholder = placeholder,
                    modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth())
                note?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = VibyColors.AccentBright)
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(220.dp)) {
                    if (groups.isEmpty()) EmptyHint(empty.first, empty.second)
                    else EmptyHint("Ничего не найдено", "Попробуйте другой запрос.")
                }
            }
        }
        items(visible, key = { it.key }) { group ->
            GroupRow(
                seed = group.key,
                cover = group.cover,
                name = group.name,
                detail = listOfNotNull(group.detail, tracksLabel(group.tracks.size)).joinToString(" • "),
            ) { state.navigate(Screen.Collection(ref(group))) }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}
