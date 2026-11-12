package com.viby.core.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.viby.core.ytdlp.YtDlpRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

class MediaConverterTest {

    @TempDir
    File directory;

    private final MediaConverter converter = new MediaConverter();

    @Test
    void configureKeepsExistingFfmpegPostProcessingContract() {
        YtDlpRequest request = new YtDlpRequest("url");
        converter.configure(request, "m4a");
        assertTrue(request.hasOption("-x"));
        assertEquals("m4a", request.getOption("--audio-format"));
        assertEquals("0", request.getOption("--audio-quality"));
        assertTrue(request.hasOption("--embed-thumbnail"));
        assertTrue(request.hasOption("--embed-metadata"));
    }

    @Test
    void mp3UsesTheBitrateFromSettingsAndM4aKeepsTheSource() {
        MediaConverter chosen = new MediaConverter(() -> "192K");
        YtDlpRequest mp3 = new YtDlpRequest("url");
        chosen.configure(mp3, "mp3");
        assertEquals("192K", mp3.getOption("--audio-quality"));
        YtDlpRequest m4a = new YtDlpRequest("url");
        chosen.configure(m4a, "m4a");
        assertEquals("0", m4a.getOption("--audio-quality"));
    }

    @Test
    void findOutputPrefersRequestedFormat() throws Exception {
        File expected = new File(directory, "song.mp3");
        assertTrue(expected.createNewFile());
        assertEquals(expected, converter.findOutput(directory, "song", "mp3"));
    }

    @Test
    void findOutputAcceptsActualFallbackExtension() throws Exception {
        File actual = new File(directory, "song.m4a");
        assertTrue(actual.createNewFile());
        assertEquals(actual, converter.findOutput(directory, "song", "mp3"));
    }

    @Test
    void findOutputRejectsMissingConversionResult() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> converter.findOutput(directory, "song", "mp3"));
        assertTrue(error.getMessage().contains("song"));
    }
}
