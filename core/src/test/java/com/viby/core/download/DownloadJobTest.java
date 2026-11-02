package com.viby.core.download;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DownloadJobTest {

    @Test
    void newJobsHaveIndependentStateAndUniqueIds() {
        DownloadJob first = new DownloadJob("first", "A", true);
        DownloadJob second = new DownloadJob("second", "B", true);

        first.pauseRequested = true;
        first.tracks.add(new DownloadJob.TrackItem("video", "Track", "Artist", 1, 0));

        assertNotEquals(first.id, second.id);
        assertTrue(first.pauseRequested);
        assertFalse(second.pauseRequested);
        assertTrue(second.tracks.isEmpty());
    }

    @Test
    void onlyUnfinishedStatesAreActive() {
        DownloadJob job = new DownloadJob("url", "playlist", true);
        for (DownloadJob.Status status : new DownloadJob.Status[]{
                DownloadJob.Status.QUEUED,
                DownloadJob.Status.PREPARING,
                DownloadJob.Status.DOWNLOADING,
                DownloadJob.Status.PAUSED}) {
            job.status = status;
            assertTrue(job.isActive(), status.name());
        }
        for (DownloadJob.Status status : new DownloadJob.Status[]{
                DownloadJob.Status.DONE,
                DownloadJob.Status.COMPLETED_WITH_ERRORS,
                DownloadJob.Status.FAILED,
                DownloadJob.Status.CANCELED}) {
            job.status = status;
            assertFalse(job.isActive(), status.name());
        }
    }
}
