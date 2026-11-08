package com.viby.core.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

class DownloadProgressParserTest {

    @Test
    void transferFillsItsShareOfTheBar() {
        assertEquals(DownloadProgressParser.THUMBNAIL_END,
                DownloadProgressParser.resolve(0, 0f, "[download]   0.0% of 4.32MiB"));
        assertEquals(65, DownloadProgressParser.resolve(
                0, 0f, "[download]  67.3% of ~ 4.32MiB at 1.00MiB/s"));
        assertEquals(DownloadProgressParser.DOWNLOAD_END,
                DownloadProgressParser.resolve(0, 100f, "[download] 100% of 1.89MiB in 00:00:00"));
    }

    @Test
    void neverMovesBackwardsOrOutsideBounds() {
        assertEquals(80, DownloadProgressParser.resolve(80, 12f, "[download]  12.0% of 4.32MiB"));
        assertEquals(DownloadProgressParser.DOWNLOAD_END, DownloadProgressParser.resolve(0, 120f, null));
        assertEquals(30, DownloadProgressParser.resolve(30, 0f, "something unrelated"));
    }

    /** The real output of one track: the bar moves through every stage, not 0 then 100. */
    @Test
    void movesDuringPreparationAndConversion() {
        List<String> lines = List.of(
                "[youtube] Extracting URL: https://www.youtube.com/watch?v=zkGSIz9LfFo",
                "[youtube] zkGSIz9LfFo: Downloading webpage",
                "[youtube] zkGSIz9LfFo: Downloading tv samsung player API JSON",
                "[youtube] [jsc:deno] Solving JS challenges using deno",
                "[info] zkGSIz9LfFo: Downloading 1 format(s): 251",
                "[info] Downloading video thumbnail 43 ...",
                "[info] Video Thumbnail 43 does not exist",
                "[info] Downloading video thumbnail 42 ...",
                "[download] Destination: track.webm",
                "[download] 100% of    1.89MiB in 00:00:00 at 18.85MiB/s",
                "[ExtractAudio] Destination: track.mp3",
                "[EmbedThumbnail] ffmpeg: Adding thumbnail to \"track.mp3\"",
                "[Metadata] Adding metadata to \"track.mp3\"");
        int progress = 0;
        int previous = -1;
        for (String line : lines) {
            progress = DownloadProgressParser.resolve(progress, 0f, line);
            assertTrue(progress > previous, "stuck at " + progress + " on: " + line);
            previous = progress;
        }
        assertTrue(progress < 100, "100% is set when the file is done");
    }
}
