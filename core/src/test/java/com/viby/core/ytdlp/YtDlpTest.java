package com.viby.core.ytdlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class YtDlpTest {

    @Test
    void requestBuildsOptionsBeforeUrlsAndRepeatsMultiValueOptions() {
        YtDlpRequest request = new YtDlpRequest("https://youtu.be/id")
                .addOption("-x")
                .addOption("--extractor-args", "a")
                .addOption("--extractor-args", "b");
        assertEquals(List.of("-x", "--extractor-args", "a", "--extractor-args", "b",
                "https://youtu.be/id"), request.buildCommand());
        assertTrue(request.hasOption("-x"));
        assertNull(request.getOption("-x"));
        assertEquals("a", request.getOption("--extractor-args"));
    }

    @Test
    void commandAddsBundledFfmpegAndDeno() {
        YtDlp ytDlp = new YtDlp(paths(Path.of("ff"), Path.of("deno.exe")));
        List<String> command = ytDlp.command(new YtDlpRequest("url"));
        assertEquals(List.of("yt-dlp", "--ffmpeg-location", "ff",
                "--js-runtimes", "deno:deno.exe", "url"), command);
    }

    @Test
    void commandSkipsMissingTools() {
        YtDlp ytDlp = new YtDlp(paths(null, null));
        assertEquals(List.of("yt-dlp", "url"), ytDlp.command(new YtDlpRequest("url")));
    }

    @Test
    void progressParsesPercentAndEta() {
        List<Object[]> calls = new ArrayList<>();
        YtDlp.ProgressCallback callback = (progress, eta, line) ->
                calls.add(new Object[]{progress, eta});
        YtDlp.reportProgress("[download]  45.3% of    4.20MiB at  1.00MiB/s ETA 00:12", callback);
        YtDlp.reportProgress("[download]  10.0% of ~ 99.00MiB at 50.00KiB/s ETA 01:02:03",
                callback);
        YtDlp.reportProgress("[ExtractAudio] Destination: song.mp3", callback);
        assertEquals(45.3f, (float) calls.get(0)[0], 0.001);
        assertEquals(12L, calls.get(0)[1]);
        assertEquals(3723L, calls.get(1)[1]);
        assertEquals(0f, (float) calls.get(2)[0]);
        assertEquals(-1L, calls.get(2)[1]);
    }

    @Test
    void recognizesBrowserCookieFailures() {
        assertTrue(YtDlp.isCookieLoadError("ERROR: Could not copy Chrome cookie database. See ..."));
        assertTrue(YtDlp.isCookieLoadError("ERROR: Failed to decrypt with DPAPI"));
        assertFalse(YtDlp.isCookieLoadError("ERROR: [youtube] abc: Private video"));
        assertFalse(YtDlp.isCookieLoadError(null));
        YtDlpRequest request = new YtDlpRequest("url").addOption("--cookies-from-browser", "chrome");
        assertEquals(List.of("url"), request.copy().removeOption("--cookies-from-browser").buildCommand());
        assertTrue(request.hasOption("--cookies-from-browser"));
    }

    @Test
    void videoInfoFallsBackToChannelAndIgnoresBlankValues() {
        VideoInfo info = new VideoInfo(new JSONObject()
                .put("id", "abc")
                .put("title", " Song ")
                .put("channel", "Channel")
                .put("thumbnail", "")
                .put("duration", 245.6));
        assertEquals("Song", info.getTitle());
        assertEquals("Channel", info.getUploader());
        assertEquals("Channel", info.getArtist());
        assertNull(info.getThumbnail());
        assertEquals(246, info.getDuration());
        assertFalse(new VideoInfo(new JSONObject().put("artist", "A")).getArtist().isEmpty());
    }

    private static ToolPaths paths(Path ffmpeg, Path deno) {
        return new ToolPaths() {
            @Override
            public Path ytDlp() {
                return Path.of("yt-dlp");
            }

            @Override
            public Path ffmpegDirectory() {
                return ffmpeg;
            }

            @Override
            public Path deno() {
                return deno;
            }
        };
    }
}
