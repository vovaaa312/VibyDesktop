package com.viby.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viby.app.state.AppState
import com.viby.app.theme.VibyColors
import com.viby.app.ui.ScrollColumn
import com.viby.app.ui.tracksLabel
import com.viby.core.util.Formats

/** Settings → Хранилище: what the music folder holds and the maintenance actions for it. */
@Composable
fun StorageScreen(state: AppState) {
    val storage = state.storage
    LaunchedEffect(Unit) { storage.refresh() }
    val report = storage.report
    val idle = storage.busy == null

    ScrollColumn(
        Modifier.fillMaxSize(),
        contentModifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 64.dp, bottom = 24.dp),
    ) {
        Column(Modifier.widthIn(max = 760.dp)) {
            Text("Хранилище", style = MaterialTheme.typography.headlineMedium, color = VibyColors.TextPrimary)
            Text(
                report?.musicDirectory()?.toString() ?: state.settings.musicDirectory().toString(),
                style = MaterialTheme.typography.bodySmall,
                color = VibyColors.TextSecondary,
            )
            Spacer(Modifier.height(16.dp))
            val busy = storage.busy
            if (busy != null) {
                Text(busy, style = MaterialTheme.typography.bodyMedium, color = VibyColors.AccentBright)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    color = VibyColors.Accent,
                    trackColor = VibyColors.Highlight,
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
            if (report == null) return@Column

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Медиатека", Formats.bytes(report.libraryBytes()), "${report.libraryFiles()} ${filesWord(report.libraryFiles())}")
                Stat(
                    "Свободно на диске",
                    if (report.freeBytes() >= 0) Formats.bytes(report.freeBytes()) else "—",
                    "нужно не меньше ${Formats.bytes(com.viby.core.util.StorageHelper.MIN_FREE_BYTES)}",
                )
                Stat("Вне медиатеки", Formats.bytes(report.unreferencedBytes()), "${report.unreferenced().size} ${filesWord(report.unreferenced().size)}")
                Stat("Временные", Formats.bytes(report.tempBytes()), "${report.temp().size} ${filesWord(report.temp().size)}")
            }

            Section("Обслуживание") {
                SettingRow(
                    "Потерянные файлы",
                    if (report.missingFiles() == 0) "У всех скачанных треков файлы на месте"
                    else "${tracksLabel(report.missingFiles())} без файла — найти их в папке по имени и тегам YouTube",
                ) {
                    OutlinedButton(enabled = idle, onClick = { storage.scan(adopt = false) }) { Text("Найти") }
                }
                SettingRow(
                    "Файлы вне медиатеки",
                    if (report.unreferenced().isEmpty()) "Все аудиофайлы в папке относятся к плейлистам"
                    else "Аудио, которого нет ни в одном плейлисте: добавить в плейлист по имени папки или удалить",
                ) {
                    if (report.unreferenced().isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = idle, onClick = { storage.scan(adopt = true) }) { Text("Добавить") }
                            OutlinedButton(enabled = idle, onClick = storage::deleteUnreferenced) {
                                Text("Удалить", color = VibyColors.Danger)
                            }
                        }
                    }
                }
                SettingRow(
                    "Временные файлы",
                    "Недокачанные части и промежуточные файлы yt-dlp. Активные загрузки не затрагиваются",
                ) {
                    if (report.temp().isNotEmpty()) {
                        OutlinedButton(enabled = idle, onClick = storage::deleteTemp) { Text("Очистить") }
                    }
                }
                SettingRow("Пересчитать", "Обновить сведения о папке с музыкой") {
                    OutlinedButton(enabled = idle, onClick = storage::refresh) { Text("Обновить") }
                }
            }

            Section("Плейлисты") {
                val largest = report.playlists().maxOfOrNull { it.bytes() }?.coerceAtLeast(1) ?: 1
                report.playlists().forEach { usage ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(
                                    usage.name(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = VibyColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${usage.files()} ${filesWord(usage.files())} · ${Formats.bytes(usage.bytes())}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VibyColors.TextSecondary,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(VibyColors.Highlight)) {
                                Box(
                                    Modifier.fillMaxWidth(usage.bytes().toFloat() / largest).fillMaxHeight()
                                        .clip(RoundedCornerShape(2.dp)).background(VibyColors.Accent)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Stat(title: String, value: String, detail: String) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(VibyColors.Elevated).padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = VibyColors.TextSecondary, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = VibyColors.TextPrimary, maxLines = 1)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = VibyColors.TextMuted, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** "1 файл", "3 файла", "12 файлов". */
internal fun filesWord(count: Int): String = when {
    count % 10 == 1 && count % 100 != 11 -> "файл"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "файла"
    else -> "файлов"
}
