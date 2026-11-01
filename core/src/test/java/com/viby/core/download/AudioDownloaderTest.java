package com.viby.core.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.viby.core.ytdlp.VideoInfo;
import com.viby.core.ytdlp.YtDlpRequest;
import com.viby.core.ytdlp.YtDlpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

class AudioDownloaderTest {

    @TempDir
    File temporaryFolder;

    @Test
    void requestKeepsAudioMetadataAuthenticationAndEscapedTemplate() {
        AudioDownloader downloader = downloader((request, processId, listener) -> { });
        YtDlpRequest request = downloader.buildRequest(
                "https://youtu.be/id", folder("audio"), "100% song", "m4a", null);
        assertTrue(request.hasOption("--no-playlist"));
        assertTrue(request.hasOption("-x"));
        assertEquals("m4a", request.getOption("--audio-format"));
        assertEquals("0", request.getOption("--audio-quality"));
        assertTrue(request.hasOption("--embed-thumbnail"));
        assertTrue(request.hasOption("--embed-metadata"));
        assertEquals("cookie.txt", request.getOption("--cookies"));
        assertTrue(request.getOption("-o").contains("100%% song.%(ext)s"));
        assertFalse(request.hasOption("--extractor-args"));
    }

    @Test
    void alternatePlayerClientsAreAppliedOnlyWhenRequested() {
        AudioDownloader downloader = downloader((request, processId, listener) -> { });
        YtDlpRequest request = downloader.buildRequest(
                "url", folder("alternate"), "song", "mp3", "default,tv,mweb");
        assertEquals("youtube:player_client=default,tv,mweb",
                request.getOption("--extractor-args"));
    }

    @Test
    void downloadDelegatesProgressAndReturnsCreatedFile() throws Exception {
        File directory = folder("result");
        File expected = new File(directory, "song.mp3");
        final int[] callbackProgress = {-1};
        AudioDownloader downloader = downloader((request, processId, listener) -> {
            assertEquals("job-7", processId);
            assertTrue(expected.createNewFile());
            listener.onProgress(45f, 3L, "[download] 45%");
        });
        File actual = downloader.download("url", directory, "song", "mp3",
                null, "job-7", (progress, eta, line) ->
                        callbackProgress[0] = Math.round(progress));
        assertEquals(expected, actual);
        assertEquals(45, callbackProgress[0]);
    }

    private File folder(String name) {
        File folder = new File(temporaryFolder, name);
        assertTrue(folder.mkdirs());
        return folder;
    }

    private static AudioDownloader downloader(AudioDownloader.Backend backend) {
        YoutubeExtractor extractor = new YoutubeExtractor(
                request -> request.addOption("--cookies", "cookie.txt"),
                new YoutubeExtractor.Backend() {
                    @Override
                    public VideoInfo getInfo(YtDlpRequest request) {
                        return null;
                    }

                    @Override
                    public YtDlpResponse execute(YtDlpRequest request, String processId) {
                        return null;
                    }
                });
        return new AudioDownloader(extractor, new MediaConverter(), backend);
    }
}
