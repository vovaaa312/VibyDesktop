package com.viby.core;

import com.viby.core.download.DownloadManager;
import com.viby.core.platform.AppDirs;

/**
 * Manual check of the real download queue: {@code ./gradlew :core:devQueue --args="<url>"}.
 * Prints job status changes and stops after the first downloaded track or a failure.
 */
public final class DevQueue {

    public static void main(String[] args) throws Exception {
        try (VibyEngine engine = new VibyEngine(AppDirs.current())) {
            engine.settings().setMusicDirectory(engine.dirs().root().resolve("Music"));
            engine.start();
            while (!engine.isReady()) {
                if (engine.status().state() == VibyEngine.State.FAILED) {
                    throw new IllegalStateException(engine.status().error());
                }
                Thread.sleep(200);
            }
            long id = engine.downloads().enqueue(args[0], args.length > 1 ? args[1] : null);
            String last = "";
            long deadline = System.currentTimeMillis() + 300_000;
            while (System.currentTimeMillis() < deadline) {
                DownloadManager.JobSnapshot job = engine.downloads().jobs().stream()
                        .filter(j -> j.id() == id).findFirst().orElseThrow();
                String line = job.status() + " total=" + job.totalTracks() + " done=" + job.doneTracks()
                        + " failed=" + job.failedTracks() + " playlist=" + job.playlistName()
                        + " title=" + job.title() + " error=" + job.error();
                if (!line.equals(last)) {
                    System.out.println(line);
                    last = line;
                }
                if (job.isFinished() || job.doneTracks() >= 1) {
                    break;
                }
                Thread.sleep(300);
            }
            engine.downloads().cancel(id);
        }
    }
}
