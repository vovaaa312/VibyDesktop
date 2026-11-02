package com.viby.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.viby.app.state.AppState
import com.viby.app.state.JobUi
import com.viby.app.state.Screen
import com.viby.app.state.TrackItemUi
import com.viby.app.theme.VibyColors
import com.viby.app.ui.CoverArt
import com.viby.app.ui.DragHandle
import com.viby.app.ui.EmptyHint
import com.viby.app.ui.IconAction
import com.viby.app.ui.ScrollLazyColumn
import com.viby.core.download.DownloadJob.Status
import com.viby.core.download.DownloadJob.TrackItem
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.Locale
import kotlin.math.roundToInt

private val TrackRowHeight = 52.dp

/** Port of the Android download screen: job queue, per-playlist track queue, failure details. */
@Composable
fun DownloadsScreen(state: AppState) {
    val jobs = state.downloads.jobs
    var expanded by remember { mutableStateOf(emptySet<Long>()) }
    var failure by remember { mutableStateOf<Pair<JobUi, TrackItemUi>?>(null) }
    // job drag: which job, how far, and the measured card height used as one step
    var draggingJob by remember { mutableStateOf<Long?>(null) }
    var jobDrag by remember { mutableStateOf(0f) }
    var jobStep by remember { mutableStateOf(1f) }
    var draggingTrack by remember { mutableStateOf<String?>(null) }
    var trackDrag by remember { mutableStateOf(0f) }
    val trackStep = with(LocalDensity.current) { TrackRowHeight.toPx() }

    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 64.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Загрузки", style = MaterialTheme.typography.headlineMedium, color = VibyColors.TextPrimary,
                modifier = Modifier.weight(1f))
            if (jobs.any { !it.isActive }) {
                TextButton(onClick = state.downloads::clearFinished) { Text("Очистить завершённые") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Перетащите задачу за ручку: верхняя качается первой. Очередь переживает перезапуск.",
            style = MaterialTheme.typography.bodyMedium,
            color = VibyColors.TextSecondary,
        )
        Spacer(Modifier.height(20.dp))
        if (jobs.isEmpty()) {
            EmptyHint("Нет загрузок", "Добавьте ссылку на видео или плейлист в разделе «Добавить с YouTube».")
            return
        }
        val active = jobs.filter { it.isActive }
        ScrollLazyColumn {
            jobs.forEach { job ->
                item(key = "job-${job.id}") {
                    val dragging = draggingJob == job.id
                    JobCard(
                        state, job,
                        expanded = job.id in expanded,
                        onToggleTracks = { expanded = if (job.id in expanded) expanded - job.id else expanded + job.id },
                        onDrag = { dy ->
                            draggingJob = job.id
                            jobDrag += dy
                        },
                        onDragEnd = {
                            // drop onto the active job that now sits under the dragged card
                            val from = active.indexOfFirst { it.id == job.id }
                            val to = (from + (jobDrag / jobStep).roundToInt()).coerceIn(0, active.lastIndex)
                            if (from >= 0 && to != from) state.downloads.moveJob(job.id, active[to].id)
                            draggingJob = null
                            jobDrag = 0f
                        },
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .onSizeChanged { if (!dragging) jobStep = it.height.toFloat().coerceAtLeast(1f) }
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) jobDrag else 0f },
                    )
                }
                if (job.id in expanded) {
                    item(key = "tracks-${job.id}") { TrackQueueHeader(state, job) }
                    // waiting tracks and the downloading one; dropping above the downloading
                    // track starts the dropped one, dragging it down makes it wait
                    val movable = job.tracks.filter { it.isMovable }
                    items(job.tracks, key = { "track-${job.id}-${it.videoId}" }) { track ->
                        val dragging = draggingTrack == "${job.id}:${track.videoId}"
                        TrackQueueRow(
                            job, track,
                            onFailed = { failure = job to track },
                            onFirst = { state.downloads.downloadFirst(job, track.videoId) },
                            onDrag = { dy ->
                                draggingTrack = "${job.id}:${track.videoId}"
                                trackDrag += dy
                            },
                            onDragEnd = {
                                val from = movable.indexOfFirst { it.videoId == track.videoId }
                                val to = (from + (trackDrag / trackStep).roundToInt()).coerceIn(0, movable.lastIndex)
                                if (from >= 0 && to != from) {
                                    state.downloads.moveTrack(job.id, track.videoId, movable[to].videoId)
                                }
                                draggingTrack = null
                                trackDrag = 0f
                            },
                            modifier = Modifier
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer { translationY = if (dragging) trackDrag else 0f },
                        )
                    }
                    item(key = "tracks-end-${job.id}") { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }

    failure?.let { (job, track) -> FailureDialog(state, job, track) { failure = null } }
}

@Composable
private fun JobCard(
    state: AppState,
    job: JobUi,
    expanded: Boolean,
    onToggleTracks: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier,
) {
    val playlist = job.playlistName?.let(state.library::playlist)
    val paused = job.status == Status.PAUSED || job.pauseRequested
    val running = job.status == Status.DOWNLOADING || job.status == Status.PREPARING || job.status == Status.PAUSED
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(VibyColors.Elevated)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (job.isActive) {
            DragHandle(onDrag, onDragEnd)
        } else {
            Spacer(Modifier.width(24.dp))
        }
        Spacer(Modifier.width(8.dp))
        CoverArt(
            "job-${job.id}", 56.dp,
            downloadProgress = if (job.status == Status.DOWNLOADING) job.progress else null,
            imageUrl = playlist?.tracks?.firstOrNull { it.videoId == job.activeVideoId }?.thumbnailUrl
                ?: playlist?.coverUrl,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(job.title, style = MaterialTheme.typography.titleSmall, color = VibyColors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val showTrack = job.currentTrackTitle != null && job.isPlaylist &&
                (job.status == Status.DOWNLOADING || job.status == Status.PAUSED)
            if (showTrack) {
                Text(job.currentTrackTitle, style = MaterialTheme.typography.bodySmall,
                    color = VibyColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                statusText(job),
                style = MaterialTheme.typography.bodySmall,
                color = if (job.status == Status.FAILED) VibyColors.Danger else VibyColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if ((job.status == Status.DONE || job.status == Status.COMPLETED_WITH_ERRORS) && job.failedTitles.isNotEmpty()) {
                Text(
                    "Не удалось скачать:" + job.failedTitles.joinToString("") { "\n• $it" },
                    style = MaterialTheme.typography.bodySmall,
                    color = VibyColors.Danger,
                    maxLines = 20,
                )
            }
            if (running) {
                Spacer(Modifier.height(8.dp))
                if (job.status == Status.PREPARING) {
                    LinearProgressIndicator(color = VibyColors.Accent, trackColor = VibyColors.Highlight,
                        modifier = Modifier.fillMaxWidth().height(4.dp))
                } else {
                    LinearProgressIndicator(
                        progress = { job.progress / 100f },
                        color = if (paused) VibyColors.TextMuted else VibyColors.Accent,
                        trackColor = VibyColors.Highlight,
                        strokeCap = StrokeCap.Round,
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        if (job.isPlaylist && (job.tracks.isNotEmpty() || job.isActive)) {
            IconAction(Icons.AutoMirrored.Rounded.QueueMusic, if (expanded) "Скрыть треки" else "Треки",
                onToggleTracks, active = expanded)
        }
        when (job.status) {
            Status.DONE -> Icon(Icons.Rounded.CheckCircle, "Готово", tint = VibyColors.AccentBright)
            Status.COMPLETED_WITH_ERRORS -> Icon(Icons.Rounded.Warning, "Есть ошибки", tint = VibyColors.Danger)
            Status.FAILED -> Icon(Icons.Rounded.ErrorOutline, "Ошибка", tint = VibyColors.Danger)
            Status.CANCELED -> Unit
            else -> {
                IconAction(
                    if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    if (paused) "Продолжить" else "Пауза",
                    { if (paused) state.downloads.resume(job.id) else state.downloads.pause(job.id) },
                )
                IconAction(Icons.Rounded.Close, "Отменить", { state.downloads.cancel(job.id) })
            }
        }
        if (!job.isActive && playlist != null) {
            TextButton(onClick = { state.navigate(Screen.PlaylistScreen(playlist.id)) }) { Text("Открыть") }
        }
    }
}

@Composable
private fun TrackQueueHeader(state: AppState, job: JobUi) {
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (job.tracks.isEmpty()) "Список появится после получения информации о плейлисте"
            else "Перетаскивайте треки: тот, что выше, скачивается первым",
            style = MaterialTheme.typography.bodySmall,
            color = VibyColors.TextMuted,
            modifier = Modifier.weight(1f),
        )
        if (job.failedCount > 0 && !job.isActive) {
            TextButton(onClick = { state.downloads.retryAllFailed(job.id) }) { Text("Повторить все неудачные") }
        }
    }
}

@Composable
private fun TrackQueueRow(
    job: JobUi,
    track: TrackItemUi,
    onFailed: () -> Unit,
    onFirst: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val movable = track.isMovable && job.isActive
    Row(
        modifier
            .fillMaxWidth()
            .height(TrackRowHeight)
            .padding(start = 40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) VibyColors.Hover else Color.Transparent)
            .hoverable(interaction)
            .then(if (track.isFailed) Modifier.clickable(onClick = onFailed) else Modifier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (movable) DragHandle(onDrag, onDragEnd) else Spacer(Modifier.width(24.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyMedium, color = VibyColors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(track.uploader, trackStatus(track)).joinToString(" — "),
                style = MaterialTheme.typography.bodySmall,
                color = if (track.isFailed) VibyColors.Danger else VibyColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            track.status == TrackItem.Status.DOWNLOADING -> CircularProgressIndicator(
                progress = { track.progress / 100f }, color = VibyColors.Accent,
                trackColor = VibyColors.Highlight, strokeWidth = 2.dp, modifier = Modifier.size(20.dp),
            )
            track.status == TrackItem.Status.DOWNLOADED ->
                Icon(Icons.Rounded.CheckCircle, "Скачан", tint = VibyColors.AccentBright, modifier = Modifier.size(20.dp))
            movable && hovered ->
                IconAction(Icons.Rounded.VerticalAlignTop, "Скачать первым", onFirst, size = 28.dp, iconSize = 18.dp)
        }
    }
}

/** Android DownloadTrackQueueDialogFragment: why it failed, retry, remove, copy diagnostics. */
@Composable
private fun FailureDialog(state: AppState, job: JobUi, track: TrackItemUi, onDismiss: () -> Unit) {
    val reason = track.failureMessage ?: "Неизвестная ошибка загрузки"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VibyColors.Elevated,
        title = { Text("Почему трек не загрузился?") },
        text = { Text("${track.title}\n\n$reason") },
        confirmButton = {
            TextButton(onClick = { state.downloads.retryTrack(job.id, track.videoId); onDismiss() }) {
                Text("Повторить")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    val diagnostic = "Сбой загрузки Viby\nТрек: ${track.title}\nVideo ID: ${track.videoId}\n" +
                        "Понятная причина: $reason\nВсего попыток: ${track.attemptCount}"
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(diagnostic), null)
                    state.showMessage("Диагностика скопирована")
                }) { Text("Скопировать диагностику") }
                TextButton(onClick = { state.downloads.removeFailedTrack(job.id, track.videoId); onDismiss() }) {
                    Text("Удалить незагруженную запись", color = VibyColors.Danger)
                }
            }
        },
    )
}

/** Android DownloadsAdapter.statusText. */
private fun statusText(job: JobUi): String = when (job.status) {
    Status.QUEUED -> "В очереди"
    Status.PREPARING -> "Получаю информацию…"
    Status.PAUSED, Status.DOWNLOADING -> buildList {
        if (job.waitingForNetwork || job.networkRetrying) add("Ошибка сети — повторяем автоматически")
        if (job.isPlaylist && job.totalCount > 0) add("Трек ${job.currentIndex} из ${job.totalCount}")
        if (job.totalBytes > 0) add("${size(job.downloadedBytes)} / ${size(job.totalBytes)}")
        add("${job.progress}%")
        if (job.status == Status.PAUSED) add("Пауза")
    }.joinToString(" · ")
    Status.DONE -> "Готово · ${job.playlistName ?: ""}"
    Status.COMPLETED_WITH_ERRORS -> "Завершено с ошибками · не загружено: ${job.failedCount}"
    Status.FAILED -> job.error ?: "Ошибка"
    Status.CANCELED -> "Отменено"
}

/** Android DownloadTrackQueueAdapter status labels. */
private fun trackStatus(track: TrackItemUi): String = when (track.status) {
    TrackItem.Status.DOWNLOADING -> "Скачивается ${track.progress}%"
    TrackItem.Status.DOWNLOADED -> "Скачан"
    TrackItem.Status.RETRY_SCHEDULED -> "Запланирован повтор · попытка ${track.attemptCount + 1}"
    TrackItem.Status.FAILED_TEMPORARY, TrackItem.Status.FAILED_PERMANENT -> track.failureMessage ?: "Ошибка"
    TrackItem.Status.WAITING -> "Ожидает"
}

/** Android Formats.size: 2264924 → "2,2 МБ". */
private fun size(bytes: Long): String =
    if (bytes < 1024 * 1024) String.format(Locale.getDefault(), "%.0f КБ", bytes.coerceAtLeast(0) / 1024f)
    else String.format(Locale.getDefault(), "%.1f МБ", bytes / (1024f * 1024f))

private val TrackItemUi.isMovable: Boolean
    get() = status == TrackItem.Status.WAITING || status == TrackItem.Status.DOWNLOADING
