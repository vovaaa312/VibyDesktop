package com.viby.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import com.viby.app.model.Playlist
import com.viby.app.state.AppState
import com.viby.app.state.Screen
import com.viby.app.theme.VibyColors

/** True in short windows: tighter navigation and an icon row instead of the bottom menu. */
private val LocalCompactSidebar = compositionLocalOf { false }

@Composable
fun Sidebar(state: AppState, modifier: Modifier = Modifier, width: Dp = 300.dp) {
    BoxWithConstraints(modifier.width(width).fillMaxHeight()) {
        // below this height the playlist list would shrink to a couple of rows
        CompositionLocalProvider(LocalCompactSidebar provides (maxHeight < 720.dp)) {
            SidebarContent(state)
        }
    }
}

@Composable
private fun SidebarContent(state: AppState) {
    var dialog by remember { mutableStateOf<PlaylistDialog?>(null) }
    val compact = LocalCompactSidebar.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                NavItem(Icons.Rounded.Home, "Главная", state.screen == Screen.Home) { state.navigate(Screen.Home) }
                NavItem(Icons.Rounded.AddLink, "Добавить с YouTube", state.screen == Screen.Add) {
                    state.navigate(Screen.Add)
                }
                NavItem(
                    Icons.Rounded.Download, "Загрузки", state.screen == Screen.Downloads,
                    badge = state.downloads.activeCount.takeIf { it > 0 },
                ) { state.navigate(Screen.Downloads) }
                if (state.account.isConfigured) {
                    NavItem(Icons.Rounded.VideoLibrary, "Мои плейлисты YouTube", state.screen == Screen.YoutubePlaylists) {
                        state.navigate(Screen.YoutubePlaylists)
                    }
                }
            }
        }
        Panel(Modifier.fillMaxWidth().weight(1f)) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.LibraryMusic, null, tint = VibyColors.TextSecondary)
                    Spacer(Modifier.width(12.dp))
                    Text("Медиатека", style = MaterialTheme.typography.titleMedium,
                        color = VibyColors.TextSecondary, modifier = Modifier.weight(1f))
                    IconAction(Icons.Rounded.Add, "Создать плейлист", { dialog = PlaylistDialog.Create })
                }
                ScrollLazyColumn(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    items(state.library.playlists, key = { it.id }) { playlist ->
                        ContextMenuBox(header = playlist.name, actions = {
                            buildList {
                                add(MenuAction("Воспроизвести") { state.playPlaylist(playlist) })
                                add(MenuAction("Перемешать") { state.playPlaylist(playlist, shuffle = true) })
                                add(MenuAction("Переименовать") { dialog = PlaylistDialog.Rename(playlist) })
                                add(MenuAction("Добавить файлы с компьютера…") { state.library.importFiles(playlist.name) })
                                if (state.account.isSignedIn && !playlist.canSync) {
                                    add(MenuAction("Сохранить на YouTube") { state.account.saveToYoutube(playlist.name) })
                                }
                                add(MenuAction("Удалить", danger = true) { dialog = PlaylistDialog.Delete(playlist) })
                            }
                        }) {
                            PlaylistItem(
                                playlist = playlist,
                                selected = state.screen == Screen.PlaylistScreen(playlist.id),
                                playing = state.player.contextPlaylistId == playlist.id && state.player.isPlaying,
                            ) { state.navigate(Screen.PlaylistScreen(playlist.id)) }
                        }
                    }
                }
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            if (compact) {
                CompactBottomBar(state)
            } else {
                Column(Modifier.padding(vertical = 4.dp)) {
                    UpdateItem(state)
                    NavItem(Icons.Rounded.GraphicEq, "Эквалайзер", state.screen == Screen.Equalizer) {
                        state.navigate(Screen.Equalizer)
                    }
                    NavItem(Icons.Rounded.Settings, "Настройки", state.screen == Screen.Settings) {
                        state.navigate(Screen.Settings)
                    }
                }
            }
        }
    }

    when (val current = dialog) {
        PlaylistDialog.Create -> NameDialog("Новый плейлист", "", "Создать", { dialog = null }) { name ->
            state.library.create(name) { state.navigate(Screen.PlaylistScreen(it)) }
        }
        is PlaylistDialog.Rename -> NameDialog("Переименовать", current.playlist.name, "Сохранить", { dialog = null }) {
            val viewing = state.screen == Screen.PlaylistScreen(current.playlist.id)
            state.library.rename(current.playlist.id, it)
            if (viewing) state.navigate(Screen.PlaylistScreen(it))
        }
        is PlaylistDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            containerColor = VibyColors.Elevated,
            title = { Text("Удалить «${current.playlist.name}»?") },
            text = {
                Text("Скачанные файлы этого плейлиста будут удалены с диска, если они не используются в других плейлистах.")
            },
            confirmButton = {
                TextButton(onClick = {
                    if (state.screen == Screen.PlaylistScreen(current.playlist.id)) state.navigate(Screen.Home)
                    state.library.delete(current.playlist.id)
                    dialog = null
                }) { Text("Удалить", color = VibyColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Отмена") } },
        )
        null -> Unit
    }
}

/** Short windows: updates, equalizer and settings as one row of icons. */
@Composable
private fun CompactBottomBar(state: AppState) {
    val updates = state.updates
    val update = updates.available
    val progress = updates.downloadProgress
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(
                Icons.Rounded.SystemUpdateAlt,
                when {
                    update != null -> "Обновить до ${update.version()}"
                    else -> "Проверить обновления (Viby ${updates.version})"
                },
                { if (update != null) updates.prompt = true else updates.checkNow() },
                size = 40.dp, iconSize = 24.dp, active = update != null,
            )
            IconAction(Icons.Rounded.GraphicEq, "Эквалайзер", { state.navigate(Screen.Equalizer) },
                size = 40.dp, iconSize = 24.dp, active = state.screen == Screen.Equalizer)
            IconAction(Icons.Rounded.Settings, "Настройки", { state.navigate(Screen.Settings) },
                size = 40.dp, iconSize = 24.dp, active = state.screen == Screen.Settings)
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                color = VibyColors.Accent,
                trackColor = VibyColors.Highlight,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(3.dp),
            )
        }
    }
}

/** App update entry: manual check, or a highlighted "update to vX" with download progress. */
@Composable
private fun UpdateItem(state: AppState) {
    val updates = state.updates
    val update = updates.available
    val progress = updates.downloadProgress
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val highlight = update != null
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    highlight -> VibyColors.Accent.copy(alpha = if (hovered) 0.35f else 0.22f)
                    hovered -> VibyColors.Hover
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) {
                if (update != null) updates.prompt = true else updates.checkNow()
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.SystemUpdateAlt, null,
            tint = if (highlight) VibyColors.AccentBright else VibyColors.TextSecondary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    progress != null -> "Скачиваю обновление… ${(progress * 100).toInt()}%"
                    update != null -> "Обновить до ${update.version()}"
                    updates.checking -> "Проверяю обновления…"
                    else -> "Проверить обновления"
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (highlight || hovered) VibyColors.TextPrimary else VibyColors.TextSecondary,
            )
            Text("Viby ${updates.version}", style = MaterialTheme.typography.bodySmall, color = VibyColors.TextMuted)
            if (progress != null) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    color = VibyColors.Accent,
                    trackColor = VibyColors.Highlight,
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
        }
    }
}

private sealed interface PlaylistDialog {
    data object Create : PlaylistDialog
    data class Rename(val playlist: Playlist) : PlaylistDialog
    data class Delete(val playlist: Playlist) : PlaylistDialog
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(VibyColors.Panel)) { content() }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    badge: Int? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color = if (selected || hovered) VibyColors.TextPrimary else VibyColors.TextSecondary
    Row(
        modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 20.dp, vertical = if (LocalCompactSidebar.current) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (badge != null) {
            Box(
                Modifier.clip(CircleShape).background(VibyColors.Accent).padding(horizontal = 7.dp, vertical = 2.dp),
            ) { Text("$badge", style = MaterialTheme.typography.labelMedium, color = Color.White) }
        }
    }
}

@Composable
private fun PlaylistItem(playlist: Playlist, selected: Boolean, playing: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    selected -> VibyColors.Selected
                    hovered -> VibyColors.Hover
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(playlist.id, 48.dp, imageUrl = playlist.coverUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (playing) VibyColors.AccentBright else VibyColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playlist.canSync) {
                    Icon(Icons.Rounded.Sync, "Связан с плейлистом YouTube",
                        tint = VibyColors.AccentBright, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    "Плейлист • ${tracksLabel(playlist.tracks.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = VibyColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
        if (playing) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = VibyColors.AccentBright, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VibyColors.Elevated,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Название") })
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()); onDismiss() }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Russian plural: 1 трек, 2 трека, 5 треков. */
fun tracksLabel(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val word = when {
        mod100 in 11..14 -> "треков"
        mod10 == 1 -> "трек"
        mod10 in 2..4 -> "трека"
        else -> "треков"
    }
    return "$count $word"
}
