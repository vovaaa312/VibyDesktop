package com.viby.core.download;

import org.jspecify.annotations.Nullable;

import com.viby.core.data.PendingDownload;

/** Creates runtime jobs from new requests or durable queue records. */
final class DownloadJobFactory {

    private DownloadJobFactory() {
    }

    static DownloadJob create(String url, @Nullable String playlistName,
                              boolean isPlaylist, long pendingId,
                              long restoredPosition, boolean restoredPaused,
                              @Nullable String restoredTrackOrder,
                              long fallbackPosition) {
        DownloadJob job = new DownloadJob(url.trim(), playlistName, isPlaylist);
        job.pendingId = pendingId;
        job.queuePosition = restoredPosition >= 0
                ? restoredPosition : fallbackPosition;
        job.pauseRequested = restoredPaused;
        job.trackOrderJson = restoredTrackOrder;
        job.status = restoredPaused
                ? DownloadJob.Status.PAUSED : DownloadJob.Status.QUEUED;
        return job;
    }

    static DownloadJob restore(PendingDownload pending) {
        DownloadJob job = create(pending.url, pending.playlistName, pending.isPlaylist,
                pending.id, pending.queuePosition, pending.paused,
                pending.trackOrderJson, pending.queuePosition);
        job.entriesJson = pending.entriesJson;
        return job;
    }
}
