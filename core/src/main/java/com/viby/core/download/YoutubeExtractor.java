package com.viby.core.download;

import com.viby.core.auth.CookieSource;
import com.viby.core.ytdlp.VideoInfo;
import com.viby.core.ytdlp.YtDlp;
import com.viby.core.ytdlp.YtDlpRequest;
import com.viby.core.ytdlp.YtDlpResponse;

import java.util.function.Supplier;

/** Builds and executes yt-dlp metadata requests. Download transfer stays in AudioDownloader. */
public final class YoutubeExtractor {

    static final int RADIO_TRACK_LIMIT = 50;

    interface Authentication {
        void apply(YtDlpRequest request);
    }

    interface Backend {
        VideoInfo getInfo(YtDlpRequest request) throws Exception;

        YtDlpResponse execute(YtDlpRequest request, String processId)
                throws Exception;
    }

    private final Authentication authentication;
    private final Backend backend;

    public static YoutubeExtractor create(YtDlp ytDlp, Supplier<CookieSource> cookies) {
        return new YoutubeExtractor(request -> cookies.get().apply(request), new Backend() {
            @Override
            public VideoInfo getInfo(YtDlpRequest request) throws Exception {
                return ytDlp.getInfo(request);
            }

            @Override
            public YtDlpResponse execute(YtDlpRequest request,
                                         String processId) throws Exception {
                return ytDlp.execute(request, processId, null);
            }
        });
    }

    YoutubeExtractor(Authentication authentication, Backend backend) {
        this.authentication = authentication;
        this.backend = backend;
    }

    YtDlpRequest videoInfoRequest(String url) {
        YtDlpRequest request = new YtDlpRequest(url);
        applyAuthentication(request);
        return request;
    }

    YtDlpRequest playlistInfoRequest(String url, boolean radioMix) {
        YtDlpRequest request = new YtDlpRequest(url);
        request.addOption("--flat-playlist");
        request.addOption("--dump-single-json");
        request.addOption("--no-warnings");
        request.addOption("--extractor-args", "youtubetab:skip=authcheck");
        if (radioMix) {
            request.addOption("--playlist-end", String.valueOf(RADIO_TRACK_LIMIT));
        }
        applyAuthentication(request);
        return request;
    }

    void applyAuthentication(YtDlpRequest request) {
        authentication.apply(request);
    }

    VideoInfo getVideoInfo(YtDlpRequest request) throws Exception {
        return backend.getInfo(request);
    }

    YtDlpResponse getPlaylistInfo(YtDlpRequest request, String processId)
            throws Exception {
        return backend.execute(request, processId);
    }
}
