package com.viby.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viby.app.state.AppState
import com.viby.app.state.Screen
import com.viby.app.theme.VibyColors
import com.viby.app.ui.CoverArt
import com.viby.app.ui.IconAction
import com.viby.app.ui.NameDialog
import com.viby.core.util.YoutubeUrlParser
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

private enum class UrlKind(val label: String) {
    VIDEO("Видео"), PLAYLIST("Плейлист"), RADIO("Радио-микс"), INVALID("Не ссылка YouTube")
}

private fun classify(url: String): UrlKind? = when {
    url.isBlank() -> null
    YoutubeUrlParser.isRadioMixUrl(url) -> UrlKind.RADIO
    YoutubeUrlParser.playlistId(url) != null && YoutubeUrlParser.videoId(url) == null -> UrlKind.PLAYLIST
    YoutubeUrlParser.videoId(url) != null -> UrlKind.VIDEO
    YoutubeUrlParser.playlistId(url) != null -> UrlKind.PLAYLIST
    else -> UrlKind.INVALID
}

@Composable
fun AddScreen(state: AppState) {
    var url by remember { mutableStateOf("") }
    var targetId by remember { mutableStateOf(state.library.playlists.firstOrNull()?.id) }
    var creating by remember { mutableStateOf(false) }
    val kind = classify(url.trim())

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            Text("Добавить с YouTube", style = MaterialTheme.typography.headlineMedium, color = VibyColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                "Вставьте ссылку на видео, плейлист или микс. Треки скачаются в MP3 с обложкой и тегами.",
                style = MaterialTheme.typography.bodyLarge,
                color = VibyColors.TextSecondary,
            )
            Spacer(Modifier.height(28.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(VibyColors.Elevated)
                    .border(1.dp, if (kind == UrlKind.INVALID) VibyColors.Danger else VibyColors.Highlight,
                        RoundedCornerShape(28.dp))
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Link, null, tint = VibyColors.TextSecondary)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (url.isEmpty()) {
                        Text("https://www.youtube.com/watch?v=…", color = VibyColors.TextMuted, fontSize = 16.sp)
                    }
                    BasicTextField(
                        url, { url = it },
                        singleLine = true,
                        textStyle = TextStyle(color = VibyColors.TextPrimary, fontSize = 16.sp),
                        cursorBrush = SolidColor(VibyColors.Accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconAction(Icons.Rounded.ContentPaste, "Вставить из буфера", { clipboardText()?.let { url = it.trim() } },
                    size = 40.dp)
            }
            if (kind != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    kind.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (kind == UrlKind.INVALID) VibyColors.Danger else VibyColors.AccentBright,
                    modifier = Modifier.padding(start = 20.dp),
                )
            }

            Spacer(Modifier.height(32.dp))
            Text("Куда сохранить", style = MaterialTheme.typography.titleMedium, color = VibyColors.TextPrimary)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlaylistPicker(state, targetId) { targetId = it }
                Text(
                    "+ Новый плейлист",
                    color = VibyColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { creating = true }.padding(8.dp),
                )
            }

            Spacer(Modifier.height(36.dp))
            Button(
                enabled = kind != null && kind != UrlKind.INVALID && targetId != null,
                onClick = {
                    val error = state.downloads.enqueue(url.trim(), targetId!!)
                    if (error != null) state.showMessage(error) else {
                        url = ""
                        state.showMessage("Добавлено в очередь загрузок")
                        state.navigate(Screen.Downloads)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = VibyColors.Accent, contentColor = Color.Black),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.height(48.dp),
            ) {
                Text("Скачать", style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    if (creating) {
        NameDialog("Новый плейлист", "", "Создать", { creating = false }) { targetId = state.library.create(it).id }
    }
}

@Composable
private fun PlaylistPicker(state: AppState, selectedId: String?, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = selectedId?.let(state.library::playlist)
    Box {
        Row(
            Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(VibyColors.Elevated)
                .clickable { open = true }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                CoverArt(selected.id, 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(selected.name, color = VibyColors.TextPrimary)
            } else {
                Text("Выберите плейлист", color = VibyColors.TextMuted, modifier = Modifier.padding(8.dp))
            }
        }
        DropdownMenu(open, { open = false }, containerColor = VibyColors.Highlight) {
            state.library.playlists.forEach { playlist ->
                DropdownMenuItem(
                    leadingIcon = { CoverArt(playlist.id, 28.dp, Modifier.size(28.dp)) },
                    text = { Text(playlist.name, color = VibyColors.TextPrimary) },
                    onClick = { onSelect(playlist.id); open = false },
                )
            }
        }
    }
}

private fun clipboardText(): String? = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
}.getOrNull()
