package com.viby.core.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.viby.core.ytdlp.VideoInfo;
import com.viby.core.ytdlp.YtDlpRequest;
import com.viby.core.ytdlp.YtDlpResponse;

import org.junit.jupiter.api.Test;

class YoutubeExtractorTest {

    @Test
    void videoRequestUsesAuthenticationWithoutPlaylistFlags() {
        YtDlpRequest request = extractor().videoInfoRequest("https://youtu.be/id");
        assertEquals("cookies.txt", request.getOption("--cookies"));
        assertFalse(request.hasOption("--flat-playlist"));
        assertTrue(request.buildCommand().contains("https://youtu.be/id"));
    }

    @Test
    void playlistRequestKeepsClassificationAndRadioOptions() {
        YoutubeExtractor extractor = extractor();
        YtDlpRequest normal = extractor.playlistInfoRequest("playlist", false);
        YtDlpRequest radio = extractor.playlistInfoRequest("radio", true);
        assertTrue(normal.hasOption("--flat-playlist"));
        assertTrue(normal.hasOption("--dump-single-json"));
        assertTrue(normal.hasOption("--no-warnings"));
        assertEquals("youtubetab:skip=authcheck",
                normal.getOption("--extractor-args"));
        assertFalse(normal.hasOption("--playlist-end"));
        assertEquals(String.valueOf(YoutubeExtractor.RADIO_TRACK_LIMIT),
                radio.getOption("--playlist-end"));
        assertEquals("cookies.txt", radio.getOption("--cookies"));
    }

    @Test
    void metadataExecutionIsDelegatedToBackend() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        YoutubeExtractor extractor = new YoutubeExtractor(request -> { }, backend);
        YtDlpRequest request = new YtDlpRequest("url");
        assertNull(extractor.getVideoInfo(request));
        assertNull(extractor.getPlaylistInfo(request, "process"));
        assertEquals(1, backend.infoCalls);
        assertEquals(1, backend.playlistCalls);
        assertEquals("process", backend.processId);
    }

    private static YoutubeExtractor extractor() {
        return new YoutubeExtractor(
                request -> request.addOption("--cookies", "cookies.txt"),
                new RecordingBackend());
    }

    private static final class RecordingBackend implements YoutubeExtractor.Backend {
        int infoCalls;
        int playlistCalls;
        String processId;

        @Override
        public VideoInfo getInfo(YtDlpRequest request) {
            infoCalls++;
            return null;
        }

        @Override
        public YtDlpResponse execute(YtDlpRequest request, String processId) {
            playlistCalls++;
            this.processId = processId;
            return null;
        }
    }
}
