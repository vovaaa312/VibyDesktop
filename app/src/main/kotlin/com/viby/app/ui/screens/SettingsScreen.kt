package com.viby.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.viby.app.state.AppState
import com.viby.app.theme.VibyColors
import com.viby.core.auth.CookieSource
import com.viby.core.settings.Settings
import com.viby.core.tools.Tool
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun SettingsScreen(state: AppState) {
    val settings = state.settings
    var musicDir by remember { mutableStateOf(settings.musicDirectory()) }
    var format by remember { mutableStateOf(settings.audioFormat()) }
    var cookies by remember { mutableStateOf(settings.cookieSource()) }

    fun saveCookies(source: CookieSource) {
        cookies = source
        settings.setCookieSource(source)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 32.dp, top = 64.dp, bottom = 24.dp),
    ) {
        Column(Modifier.widthIn(max = 760.dp)) {
            Text("Настройки", style = MaterialTheme.typography.headlineMedium, color = VibyColors.TextPrimary)

            Section("Библиотека") {
                SettingRow("Папка с музыкой", musicDir.toString()) {
                    OutlinedButton(onClick = {
                        chooseDirectory(musicDir)?.let { musicDir = it; settings.setMusicDirectory(it) }
                    }) { Text("Изменить") }
                }
            }

            Section("Загрузка") {
                SettingRow("Формат аудио", "MP3 совместим везде, M4A сохраняет исходное качество без перекодирования") {
                    Segmented(listOf(Settings.FORMAT_MP3 to "MP3", Settings.FORMAT_M4A to "M4A"), format) {
                        format = it
                        settings.setAudioFormat(it)
                    }
                }
            }

            Section("Аккаунт YouTube") {
                Text(
                    "Нужен для возрастного контента, приватных плейлистов и «Понравившихся». " +
                        "yt-dlp берёт куки прямо из браузера, где вы вошли в YouTube.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VibyColors.TextSecondary,
                )
                Spacer(Modifier.height(16.dp))
                Segmented(
                    listOf(
                        CookieSource.Type.NONE to "Без входа",
                        CookieSource.Type.BROWSER to "Из браузера",
                        CookieSource.Type.FILE to "Файл cookies.txt",
                    ),
                    cookies.type(),
                ) { type ->
                    when (type) {
                        CookieSource.Type.NONE -> saveCookies(CookieSource.none())
                        CookieSource.Type.BROWSER -> saveCookies(CookieSource.browser("firefox", null))
                        CookieSource.Type.FILE -> chooseCookiesFile()?.let { saveCookies(CookieSource.file(it)) }
                    }
                }
                when (cookies.type()) {
                    CookieSource.Type.BROWSER -> {
                        SettingRow("Браузер", "Chrome и Edge на Windows часто шифруют куки так, что их нельзя прочитать — надёжнее Firefox") {
                            BrowserPicker(cookies.browser()!!) { saveCookies(CookieSource.browser(it, cookies.profile())) }
                        }
                    }
                    CookieSource.Type.FILE -> SettingRow("Файл", cookies.file().toString()) {
                        OutlinedButton(onClick = { chooseCookiesFile()?.let { saveCookies(CookieSource.file(it)) } }) {
                            Text("Выбрать")
                        }
                    }
                    CookieSource.Type.NONE -> Unit
                }
            }

            Section("Инструменты") {
                Tool.entries.forEach { tool ->
                    SettingRow(
                        when (tool) {
                            Tool.YT_DLP -> "yt-dlp"
                            Tool.FFMPEG -> "FFmpeg"
                            Tool.DENO -> "Deno (JS для YouTube)"
                        },
                        state.tools.installedVersion(tool) ?: "не установлен",
                    ) {}
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "yt-dlp обновляется автоматически раз в сутки. Данные приложения: ${state.dirs.root()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = VibyColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(32.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, color = VibyColors.TextPrimary)
    Spacer(Modifier.height(12.dp))
    content()
}

@Composable
private fun SettingRow(title: String, subtitle: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = VibyColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = VibyColors.TextSecondary)
        }
        Spacer(Modifier.width(16.dp))
        action()
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(VibyColors.Highlight).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) Color.Black else VibyColors.TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (active) VibyColors.TextPrimary else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun BrowserPicker(selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }) {
        Text(selected.replaceFirstChar { it.uppercase() })
        DropdownMenu(open, { open = false }, containerColor = VibyColors.Highlight) {
            CookieSource.BROWSERS.forEach { browser ->
                DropdownMenuItem(
                    text = { Text(browser.replaceFirstChar { it.uppercase() }, color = VibyColors.TextPrimary) },
                    onClick = { onSelect(browser); open = false },
                )
            }
        }
    }
}

private fun chooseDirectory(current: Path): Path? {
    val chooser = JFileChooser(current.toFile()).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Папка с музыкой"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}

private fun chooseCookiesFile(): Path? {
    val chooser = JFileChooser().apply {
        fileFilter = FileNameExtensionFilter("cookies.txt", "txt")
        dialogTitle = "Файл cookies.txt (формат Netscape)"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}
