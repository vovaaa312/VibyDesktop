package com.viby.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viby.app.state.AppState
import com.viby.app.state.Screen
import com.viby.app.theme.VibyColors
import com.viby.app.ui.CoverArt
import com.viby.app.ui.EmptyHint
import com.viby.app.ui.IconAction
import com.viby.core.download.DownloadManager.JobSnapshot
import com.viby.core.download.DownloadManager.Status

@Composable
fun DownloadsScreen(state: AppState) {
    val jobs = state.downloads.jobs
    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 64.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Загрузки", style = MaterialTheme.typography.headlineMedium, color = VibyColors.TextPrimary,
                modifier = Modifier.weight(1f))
            if (jobs.any { it.isFinished }) {
                TextButton(onClick = state.downloads::clearFinished) { Text("Очистить завершённые") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Каждая ссылка — отдельная задача: её можно приостановить или отменить, не трогая остальные. " +
                "Очередь переживает перезапуск приложения.",
            style = MaterialTheme.typography.bodyMedium,
            color = VibyColors.TextSecondary,
        )
        Spacer(Modifier.height(20.dp))
        if (jobs.isEmpty()) {
            EmptyHint("Нет загрузок", "Добавьте ссылку на видео или плейлист в разделе «Добавить с YouTube».")
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(jobs, key = { it.id() }) { job -> JobCard(state, job) }
        }
    }
}

@Composable
private fun JobCard(state: AppState, job: JobSnapshot) {
    val playlist = job.playlistName()?.let(state.library::playlist)
    val paused = job.status() == Status.PAUSED
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(VibyColors.Elevated)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            "job-${job.id()}", 56.dp,
            downloadProgress = if (job.status() == Status.DOWNLOADING) job.currentProgress() else null,
            imageUrl = playlist?.tracks?.firstOrNull { it.id == job.activeTrackId().toString() }?.thumbnailUrl
                ?: playlist?.coverUrl,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(job.title(), style = MaterialTheme.typography.titleSmall, color = VibyColors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                describe(job),
                style = MaterialTheme.typography.bodySmall,
                color = if (job.status() == Status.FAILED) VibyColors.Danger else VibyColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!job.isFinished && job.totalTracks() > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = {
                        (job.doneTracks() + job.failedTracks() + job.currentProgress() / 100f) / job.totalTracks()
                    },
                    color = if (paused) VibyColors.TextMuted else VibyColors.Accent,
                    trackColor = VibyColors.Highlight,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            } else if (job.status() == Status.RESOLVING || job.status() == Status.QUEUED) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    color = VibyColors.Accent,
                    trackColor = VibyColors.Highlight,
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        when (job.status()) {
            Status.DONE -> Icon(Icons.Rounded.CheckCircle, "Готово", tint = VibyColors.AccentBright)
            Status.DONE_WITH_ERRORS -> Icon(Icons.Rounded.Warning, "Есть ошибки", tint = VibyColors.Danger)
            Status.FAILED -> Icon(Icons.Rounded.ErrorOutline, "Ошибка", tint = VibyColors.Danger)
            else -> {
                IconAction(
                    if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    if (paused) "Продолжить" else "Пауза",
                    { if (paused) state.downloads.resume(job.id()) else state.downloads.pause(job.id()) },
                )
                IconAction(Icons.Rounded.Close, "Отменить", { state.downloads.cancel(job.id()) })
            }
        }
        if (job.isFinished && playlist != null) {
            TextButton(onClick = { state.navigate(Screen.PlaylistScreen(playlist.id)) }) { Text("Открыть") }
        }
    }
}

private fun describe(job: JobSnapshot): String {
    val target = job.playlistName()?.let { " → «$it»" } ?: ""
    val counts = "${job.doneTracks()} из ${job.totalTracks()}"
    val failed = if (job.failedTracks() > 0) ", ошибок: ${job.failedTracks()}" else ""
    return when (job.status()) {
        Status.QUEUED -> "В очереди$target"
        Status.RESOLVING -> "Получаю информацию с YouTube…"
        Status.DOWNLOADING -> "${job.currentTitle() ?: ""} • $counts$failed$target"
        Status.PAUSED -> "На паузе • $counts$target"
        Status.DONE -> "Готово • $counts$target"
        Status.DONE_WITH_ERRORS -> "Готово с ошибками • $counts$failed$target"
        Status.FAILED -> job.error() ?: "Ошибка"
    }
}
