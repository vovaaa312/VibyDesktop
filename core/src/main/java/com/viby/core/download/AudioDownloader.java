package com.viby.core.download;

import com.viby.core.ytdlp.YtDlp;
import com.viby.core.ytdlp.YtDlpRequest;

import org.jspecify.annotations.Nullable;

import java.io.File;

/** Builds and runs yt-dlp audio transfers, independent from queue and UI state. */
public final class AudioDownloader {

    public interface ProgressListener {
        void onProgress(float progress, long etaSeconds, @Nullable String outputLine);
    }

    interface Backend {
        void execute(YtDlpRequest request, String processId,
                     ProgressListener listener) throws Exception;
    }

    private final YoutubeExtractor youtubeExtractor;
    private final MediaConverter mediaConverter;
    private final Backend backend;

    public static AudioDownloader create(YtDlp ytDlp, YoutubeExtractor youtubeExtractor) {
        return create(ytDlp, youtubeExtractor, () -> "0");
    }

    /** @param mp3Quality yt-dlp --audio-quality for MP3 downloads, read for every track */
    public static AudioDownloader create(YtDlp ytDlp, YoutubeExtractor youtubeExtractor,
                                         java.util.function.Supplier<String> mp3Quality) {
        return new AudioDownloader(youtubeExtractor, new MediaConverter(mp3Quality),
                (request, processId, listener) -> ytDlp.execute(
                        request, processId, listener::onProgress));
    }

    AudioDownloader(YoutubeExtractor youtubeExtractor,
                    MediaConverter mediaConverter, Backend backend) {
        this.youtubeExtractor = youtubeExtractor;
        this.mediaConverter = mediaConverter;
        this.backend = backend;
    }

    YtDlpRequest buildRequest(String videoUrl, File directory,
                              String baseName, String format,
                              @Nullable String playerClients) {
        String template = new File(directory,
                baseName.replace("%", "%%") + ".%(ext)s").getAbsolutePath();
        YtDlpRequest request = new YtDlpRequest(videoUrl);
        request.addOption("--no-playlist");
        request.addOption("--no-mtime");
        request.addOption("-o", template);
        mediaConverter.configure(request, format);
        if (playerClients != null) {
            request.addOption("--extractor-args",
                    "youtube:player_client=" + playerClients);
        }
        youtubeExtractor.applyAuthentication(request);
        return request;
    }

    public File download(String videoUrl, File directory, String baseName,
                         String format, @Nullable String playerClients,
                         String processId, ProgressListener listener) throws Exception {
        YtDlpRequest request = buildRequest(
                videoUrl, directory, baseName, format, playerClients);
        backend.execute(request, processId, listener);
        return mediaConverter.findOutput(directory, baseName, format);
    }
}
