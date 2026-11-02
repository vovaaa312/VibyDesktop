package com.viby.core;

import com.viby.core.download.DownloadJob;
import com.viby.core.download.DownloadService;
import com.viby.core.platform.AppDirs;

import java.util.function.BooleanSupplier;

/**
 * Manual check of queue priority against real YouTube: {@code ./gradlew :core:devPriority}.
 * A job dragged above the running one must take over; a track dropped above the downloading
 * one must start; the downloading track dragged down must let the next one start.
 */
public final class DevPriority {

    private static final String FIRST = "https://www.youtube.com/watch?v=kJQP7kiw5Fk&list=RDkJQP7kiw5Fk";
    private static final String SECOND = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ";

    public static void main(String[] args) throws Exception {
        try (VibyEngine engine = new VibyEngine(AppDirs.current())) {
            engine.settings().setMusicDirectory(engine.dirs().root().resolve("Music"));
            engine.start();
            await("engine", 300, engine::isReady);
            DownloadService downloads = engine.downloads();
            current = downloads;
            // jobs restored from earlier runs would compete with the scenario
            downloads.getJobs().forEach(j -> downloads.cancel(j.id));
            await("old jobs cancelled", 60, () -> downloads.getJobs().stream().noneMatch(DownloadJob::isActive));
            downloads.clearFinished();

            downloads.enqueue(FIRST, "Prio A", true);
            DownloadJob first = await("job A starts", 120, () -> job(downloads, FIRST) != null
                    && job(downloads, FIRST).status == DownloadJob.Status.DOWNLOADING) ? job(downloads, FIRST) : null;
            downloads.enqueue(SECOND, "Prio B", true);
            DownloadJob second = await("job B queued", 30, () -> job(downloads, SECOND) != null)
                    ? job(downloads, SECOND) : null;
            System.out.println("before move: A=" + first.status + " B=" + second.status);

            downloads.moveJob(second.id, first.id);
            boolean tookOver = await("B takes over", 60, () -> second.status == DownloadJob.Status.DOWNLOADING
                    && second.activeTrack != null);
            System.out.println("after move: A=" + first.status + " B=" + second.status + " -> " + (tookOver ? "OK" : "FAIL"));

            String active = second.activeTrack.videoId;
            String last = waitingAt(second, -1);
            downloads.moveTrack(second.id, last, active);
            boolean started = await("dropped track starts", 60, () -> second.activeTrack != null
                    && last.equals(second.activeTrack.videoId));
            System.out.println("drop " + last + " above " + active + " -> " + (started ? "OK" : "FAIL"));

            String nowActive = second.activeTrack.videoId;
            String target = waitingAt(second, 1);
            String next = waitingAt(second, 0);
            downloads.moveTrack(second.id, nowActive, target);
            boolean yielded = await("next track starts", 60, () -> second.activeTrack != null
                    && next.equals(second.activeTrack.videoId));
            System.out.println("drag " + nowActive + " below " + target + ", expect " + next + " -> "
                    + (yielded ? "OK" : "FAIL (active " + (second.activeTrack != null ? second.activeTrack.videoId : null) + ")"));

            downloads.cancel(second.id);
            downloads.cancel(first.id);
        }
    }

    private static DownloadService current;
    private static String jobsLine = "";

    private static String describe() {
        if (current == null) return "";
        StringBuilder line = new StringBuilder();
        for (DownloadJob j : current.getJobs()) {
            line.append(j.playlistName).append("=").append(j.status).append(" err=").append(j.error).append("; ");
        }
        return line.toString();
    }

    private static DownloadJob job(DownloadService downloads, String url) {
        return downloads.getJobs().stream().filter(j -> j.url.equals(url)).findFirst().orElse(null);
    }

    /** Video id of the waiting track at [index] among waiting ones; negative counts from the end. */
    private static String waitingAt(DownloadJob job, int index) {
        synchronized (job.tracks) {
            var waiting = job.tracks.stream()
                    .filter(t -> t.status == DownloadJob.TrackItem.Status.WAITING).toList();
            return waiting.get(index < 0 ? waiting.size() + index : index).videoId;
        }
    }

    private static boolean await(String what, int seconds, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            jobsLine = describe();
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        System.out.println("timeout: " + what);
        System.out.println("jobs: " + jobsLine);
        return false;
    }
}
