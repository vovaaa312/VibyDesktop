package com.viby.core.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class LoudnessIndexTest {

    @TempDir
    Path temp;

    private int measured;

    /** The summary of a real track (ffmpeg ebur128, peak=true). */
    private static final String SUMMARY = """
            [Parsed_ebur128_0 @ 000001abccd2bc80] Summary:

              Integrated loudness:
                I:          -7.6 LUFS
                Threshold: -18.1 LUFS

              Loudness range:
                LRA:        10.4 LU

              True peak:
                Peak:        1.8 dBFS
            """;

    @Test
    void parsesTheEbuR128Summary() {
        LoudnessIndex.Loudness loudness = LoudnessIndex.parse(SUMMARY);
        assertEquals(-7.6, loudness.lufs(), 1e-9);
        assertEquals(1.8, loudness.peakDb(), 1e-9);
        assertNull(LoudnessIndex.parse("I: -inf LUFS"));
        assertNull(LoudnessIndex.parse("not ffmpeg output"));
    }

    @Test
    void loudTracksAreTurnedDownQuietOnesUpWithoutClipping() {
        // -7.6 LUFS → -6.4 dB to reach -14
        assertEquals(-6.4, LoudnessIndex.gain(new LoudnessIndex.Loudness(-7.6, 1.8)), 1e-9);
        // -20 LUFS wants +6 dB, but its -3 dBTP peak allows only +2
        assertEquals(2.0, LoudnessIndex.gain(new LoudnessIndex.Loudness(-20, -3)), 1e-9);
        // very quiet recordings are boosted at most 10 dB
        assertEquals(10.0, LoudnessIndex.gain(new LoudnessIndex.Loudness(-40, -30)), 1e-9);
    }

    @Test
    void filesAreMeasuredOnceAndAgainWhenTheyChange() throws Exception {
        Path song = Files.writeString(temp.resolve("song.mp3"), "audio");
        Path broken = Files.writeString(temp.resolve("broken.mp3"), "x");
        LoudnessIndex.Meter meter = file -> {
            measured++;
            return file.getName().equals("broken.mp3") ? null : new LoudnessIndex.Loudness(-10, -5);
        };
        Path cache = temp.resolve("loudness.json");
        LoudnessIndex index = new LoudnessIndex(cache, meter);
        assertNull(index.gainDb(song.toString()));

        List<String> files = List.of(song.toString(), broken.toString());
        assertEquals(2, index.refresh(files, (done, total) -> { }));
        assertEquals(-4f, index.gainDb(song.toString()), 1e-6);
        assertEquals(0f, index.gainDb(broken.toString()), 1e-6);
        assertEquals(0, index.refresh(files, (done, total) -> { }));

        // a restart reads the cache; a re-downloaded file is measured again
        LoudnessIndex restarted = new LoudnessIndex(cache, meter);
        assertEquals(-4f, restarted.gainDb(song.toString()), 1e-6);
        Files.writeString(song, "re-downloaded, another size");
        assertNull(restarted.gainDb(song.toString()));
        measured = 0;
        assertEquals(-4f, restarted.measure(song.toString()), 1e-6);
        assertEquals(1, measured);
        assertEquals(-4f, restarted.measure(song.toString()), 1e-6);
        assertEquals(1, measured);
    }
}
