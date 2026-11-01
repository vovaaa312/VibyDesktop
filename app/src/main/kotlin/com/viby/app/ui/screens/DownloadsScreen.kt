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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.unit.dp
import com.viby.app.model.DownloadJob
import com.viby.app.state.AppState
import com.viby.app.theme.VibyColors
import com.viby.app.ui.CoverArt
import com.viby.app.ui.EmptyHint
import com.viby.app.ui.IconAction

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
            "Каждый плейлист — отдельная задача: её можно приостановить или отменить, не трогая остальные.",
            style = MaterialTheme.typography.bodyMedium,
            color = VibyColors.TextSecondary,
        )
        Spacer(Modifier.height(20.dp))
        if (jobs.isEmpty()) {
            EmptyHint("Нет загрузок", "Добавьте ссылку на видео или плейлист в разделе «Добавить с YouTube».")
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(jobs, key = { it.id }) { job -> JobCard(state, job) }
        }
    }
}

@Composable
private fun JobCard(state: AppState, job: DownloadJob) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(VibyColors.Elevated)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt("job-${job.id}", 56.dp, downloadProgress = if (job.isFinished) null else job.currentProgress)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(job.title, style = MaterialTheme.typography.titleSmall, color = VibyColors.TextPrimary)
            Text(
                when {
                    job.isFinished -> "Готово • ${job.totalTracks} в «${job.playlistName}»"
                    job.paused -> "На паузе • ${job.doneTracks} из ${job.totalTracks}"
                    else -> "${job.currentTitle ?: ""} • ${job.doneTracks} из ${job.totalTracks} → «${job.playlistName}»"
                },
                style = MaterialTheme.typography.bodySmall,
                color = VibyColors.TextSecondary,
            )
            if (!job.isFinished) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (job.doneTracks + job.currentProgress / 100f) / job.totalTracks },
                    color = if (job.paused) VibyColors.TextMuted else VibyColors.Accent,
                    trackColor = VibyColors.Highlight,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        if (job.isFinished) {
            Icon(Icons.Rounded.CheckCircle, "Готово", tint = VibyColors.AccentBright)
        } else {
            IconAction(
                if (job.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                if (job.paused) "Продолжить" else "Пауза",
                { state.downloads.togglePause(job.id) },
            )
            IconAction(Icons.Rounded.Close, "Отменить", { state.downloads.cancel(job.id) })
        }
    }
}
