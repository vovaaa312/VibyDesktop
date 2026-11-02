package com.viby.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.viby.app.model.Track
import com.viby.core.download.DownloadJob
import com.viby.core.download.DownloadService
import com.viby.core.download.DuplicateTracks
import com.viby.core.util.YoutubeUrlParser
import kotlinx.coroutines.launch

/** Immutable copy of a [DownloadJob.TrackItem] for Compose. */
data class TrackItemUi(
    val videoId: String,
    val title: String,
    val uploader: String?,
    val status: DownloadJob.TrackItem.Status,
    val progress: Int,
    val failureMessage: String?,
    val attemptCount: Int,
) {
    val isFailed: Boolean
        get() = status == DownloadJob.TrackItem.Status.FAILED_TEMPORARY ||
            status == DownloadJob.TrackItem.Status.FAILED_PERMANENT
}

/** Immutable copy of a [DownloadJob]: the service mutates its jobs, Compose needs snapshots. */
data class JobUi(
    val id: Long,
    val url: String,
    val playlistName: String?,
    val isPlaylist: Boolean,
    val title: String,
    val status: DownloadJob.Status,
    val pauseRequested: Boolean,
    val currentTrackTitle: String?,
    val progress: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val waitingForNetwork: Boolean,
    val networkRetrying: Boolean,
    val currentIndex: Int,
    val totalCount: Int,
    val failedCount: Int,
    val failedTitles: List<String>,
    val error: String?,
    val activeVideoId: String?,
    val tracks: List<TrackItemUi>,
) {
    val isActive: Boolean
        get() = status == DownloadJob.Status.QUEUED || status == DownloadJob.Status.PREPARING ||
            status == DownloadJob.Status.DOWNLOADING || status == DownloadJob.Status.PAUSED

    companion object {
        /** Copies the volatile fields once; called on the thread that published the jobs. */
        fun of(job: DownloadJob): JobUi {
            val tracks = synchronized(job.tracks) {
                job.tracks.map {
                    TrackItemUi(it.videoId, it.title, it.uploader, it.status, it.progress,
                        it.failureMessage, it.attemptCount)
                }
            }
            val failed = synchronized(job.failedTitles) { job.failedTitles.toList() }
            return JobUi(
                id = job.id, url = job.url, playlistName = job.playlistName, isPlaylist = job.isPlaylist,
                title = job.title ?: job.url, status = job.status, pauseRequested = job.pauseRequested,
                currentTrackTitle = job.currentTrackTitle, progress = job.progress,
                downloadedBytes = job.downloadedBytes, totalBytes = job.totalBytes,
                waitingForNetwork = job.waitingForNetwork, networkRetrying = job.networkRetrying,
                currentIndex = job.currentIndex, totalCount = job.totalCount, failedCount = job.failedCount,
                failedTitles = failed, error = job.error, activeVideoId = job.activeTrack?.videoId,
                tracks = tracks,
            )
        }
    }
}

/** Mirrors [DownloadService] jobs into Compose state and forwards the queue actions. */
class DownloadsState(private val app: AppState) {
    var jobs by mutableStateOf<List<JobUi>>(emptyList())
        private set

    private var lastActive = emptySet<String>()
    private val service get() = app.engine.downloads()

    val activeCount: Int get() = jobs.count { it.isActive }

    /** Called on the download thread: snapshot there, publish on the UI thread. */
    internal fun onJobsChanged(live: List<DownloadJob>) {
        val snapshot = live.map(JobUi::of)
        app.scope.launch { publish(snapshot) }
    }

    private fun publish(snapshot: List<JobUi>) {
        jobs = snapshot
        app.library.applyLiveProgress(snapshot)
        // a track finished or started: placeholders changed in the database
        val active = snapshot.mapNotNull { it.activeVideoId?.let { id -> "${it.id}:$id" } }.toSet()
        if (active != lastActive) app.library.requestReload()
        lastActive = active
    }

    /** @param playlistName null = name the playlist after the YouTube title (or the default one) */
    fun enqueue(url: String, playlistName: String?, onQueued: () -> Unit = {}) {
        if (!app.engine.isReady) {
            app.showMessage("Подождите: идёт подготовка yt-dlp и FFmpeg")
            return
        }
        val clean = url.trim()
        if (service.hasActiveJob(clean, playlistName)) {
            app.showMessage("Эта ссылка уже скачивается — смотрите «Загрузки»")
            return
        }
        app.io {
            service.enqueue(clean, playlistName, DownloadService.isPlaylistUrl(clean))
            app.scope.launch { onQueued() }
        }
    }

    /**
     * Android DuplicateTrackPrompt: a single video already downloaded into the target playlist
     * asks first, then either shares the file as a second entry or downloads normally.
     * [onAccepted] gets true when a download was queued, false when the duplicate was added.
     */
    fun enqueueChecked(url: String, playlistName: String?, onAccepted: (queued: Boolean) -> Unit) {
        val clean = url.trim()
        val playlist = DuplicateTracks.targetPlaylist(playlistName)
        confirmIfDuplicate(clean, playlistName, onUnique = { enqueue(clean, playlistName) { onAccepted(true) } }) {
            videoId -> addDuplicate(playlist, videoId, clean, onAccepted)
        }
    }

    /**
     * Runs [onUnique] directly, or [onConfirmed] after the "Трек уже есть в плейлисте" question
     * when the playlist already has the downloaded video.
     */
    fun confirmIfDuplicate(url: String, playlistName: String?, onUnique: () -> Unit, onConfirmed: (videoId: String) -> Unit) {
        if (!app.engine.isReady) {
            app.showMessage("Подождите: идёт подготовка yt-dlp и FFmpeg")
            return
        }
        val isPlaylist = DownloadService.isPlaylistUrl(url)
        app.io {
            val existing = app.engine.duplicates().existing(url, playlistName, isPlaylist)
            app.scope.launch {
                if (existing == null) {
                    onUnique()
                } else {
                    val playlist = DuplicateTracks.targetPlaylist(playlistName)
                    app.ask(
                        "Трек уже есть в плейлисте",
                        "«${existing.title}» уже есть в «$playlist». Добавить ещё раз?",
                        PromptAction("Добавить повторно") { onConfirmed(existing.videoId) },
                    )
                }
            }
        }
    }

    private fun addDuplicate(playlist: String, videoId: String, url: String, onAccepted: (queued: Boolean) -> Unit) {
        app.io {
            val added = app.engine.duplicates().addDuplicate(playlist, videoId, url)
            app.scope.launch {
                if (added) {
                    app.showMessage("Повторно добавлено в «$playlist»")
                    onAccepted(false)
                } else {
                    enqueue(url, playlist) { onAccepted(true) }
                }
            }
        }
    }

    /** Android retryPersistedTrack: a failed placeholder becomes a single-track job. */
    fun retry(track: Track) {
        val source = track.sourceUrl ?: track.videoId?.let { "https://www.youtube.com/watch?v=$it" } ?: return
        enqueue(source, track.playlistName) { app.showMessage("Загрузка поставлена в очередь") }
    }

    fun radio(track: Track) {
        val videoId = track.videoId ?: return app.showMessage("Радио доступно только для треков с YouTube")
        enqueue(YoutubeUrlParser.radioMixUrl(videoId), "Радио: ${track.title}") {
            app.showMessage("Собираю радио по треку «${track.title}»")
        }
    }

    /** The user started a pending track: download it next (Android prioritizeTrack). */
    fun prioritize(track: Track) {
        val videoId = track.videoId ?: return
        app.io { service.prioritizeTrack(track.playlistName, videoId) }
    }

    fun pause(id: Long) = app.io { service.pause(id) }
    fun resume(id: Long) = app.io { service.resume(id) }
    fun cancel(id: Long) = app.io { service.cancel(id) }
    fun moveJob(id: Long, targetId: Long) = app.io { service.moveJob(id, targetId) }
    fun moveTrack(jobId: Long, videoId: String, targetVideoId: String) =
        app.io { service.moveTrack(jobId, videoId, targetVideoId) }
    fun downloadFirst(job: JobUi, videoId: String) =
        job.playlistName?.let { playlist -> app.io { service.prioritizeTrack(playlist, videoId) } }
    fun retryTrack(jobId: Long, videoId: String) = app.io { service.retryTrack(jobId, videoId) }
    fun retryAllFailed(jobId: Long) = app.io { service.retryAllFailed(jobId) }
    fun removeFailedTrack(jobId: Long, videoId: String) = app.io { service.removeFailedTrack(jobId, videoId) }
    fun clearFinished() = app.io { service.clearFinished() }
}
