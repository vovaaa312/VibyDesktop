package com.viby.core;

import com.viby.core.auth.CookieSource;
import com.viby.core.download.AudioDownloader;
import com.viby.core.download.YoutubeExtractor;
import com.viby.core.platform.AppDirs;
import com.viby.core.platform.Platform;
import com.viby.core.tools.BinaryManager;
import com.viby.core.ytdlp.YtDlp;

import java.io.File;

/**
 * Manual end-to-end check: {@code ./gradlew :core:devDownload --args="<url>"}.
 * Installs the tools into build/dev-home and downloads one video as MP3.
 */
public final class DevDownload {

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "https://www.youtube.com/watch?v=BaW_jenozKc";
        AppDirs dirs = AppDirs.current();
        BinaryManager tools = new BinaryManager(dirs.tools(), Platform.current());
        long[] lastReport = {0};
        tools.installMissing((tool, done, total) -> {
            if (System.currentTimeMillis() - lastReport[0] > 1000 || done == total) {
                lastReport[0] = System.currentTimeMillis();
                System.out.printf("%s: %d / %d MB%n", tool, done >> 20, total >> 20);
            }
        });
        YtDlp ytDlp = new YtDlp(tools);
        System.out.println("yt-dlp " + ytDlp.version() + ", tools in " + dirs.tools());

        YoutubeExtractor extractor = YoutubeExtractor.create(ytDlp, CookieSource::none);
        AudioDownloader downloader = AudioDownloader.create(ytDlp, extractor);
        File directory = new File(dirs.root().toFile(), "Music");
        directory.mkdirs();
        File result = downloader.download(url, directory, "dev-track", "mp3", null,
                "dev", (progress, eta, line) -> {
                    if (line != null) {
                        System.out.println(line);
                    }
                });
        System.out.println("Downloaded: " + result + " (" + result.length() + " bytes)");
    }
}
