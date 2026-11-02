package com.viby.core;

import com.viby.core.download.DownloadJob;
import com.viby.core.download.DownloadService;
import com.viby.core.platform.AppDirs;

/**
 * Manual check of the real download queue: {@code ./gradlew :core:devQueue --args="<url> [playlist]"}.
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
            String url = args[0];
            engine.downloads().enqueue(url, args.length > 1 ? args[1] : null, DownloadService.isPlaylistUrl(url));
            String last = "";
            long deadline = System.currentTimeMillis() + 300_000;
            DownloadJob job = null;
            while (System.currentTimeMillis() < deadline) {
                job = engine.downloads().getJobs().stream().filter(j -> j.url.equals(url)).findFirst().orElse(null);
                if (job != null) {
                    String line = job.status + " " + job.currentIndex + "/" + job.totalCount
                            + " failed=" + job.failedCount + " playlist=" + job.playlistName
                            + " title=" + job.title + " error=" + job.error;
                    if (!line.equals(last)) {
                        System.out.println(line);
                        last = line;
                    }
                    if (!job.isActive() || job.currentIndex > 1) {
                        break;
                    }
                }
                Thread.sleep(300);
            }
            if (job != null) {
                engine.downloads().cancel(job.id);
            }
        }
    }
}
