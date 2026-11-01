package com.viby.core.download;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DownloadProgressParserTest {

    @Test
    void usesCallbackProgress() {
        assertEquals(42, DownloadProgressParser.resolve(10, 42.4f, null));
    }

    @Test
    void parsesYtDlpLineWhenCallbackIsZero() {
        assertEquals(67, DownloadProgressParser.resolve(
                0, 0f, "[download]  67.3% of ~ 4.32MiB at 1.00MiB/s"));
    }

    @Test
    void neverMovesBackwardsOrOutsideBounds() {
        assertEquals(80, DownloadProgressParser.resolve(
                80, 12f, "[download]  12.0% of 4.32MiB"));
        assertEquals(100, DownloadProgressParser.resolve(0, 120f, null));
    }
}
