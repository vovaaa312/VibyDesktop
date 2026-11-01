package com.viby.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FormatsTest {

    @Test
    void durationFormatsBoundariesAndHours() {
        assertEquals("0:00", Formats.duration(-1));
        assertEquals("0:00", Formats.duration(999));
        assertEquals("0:59", Formats.duration(59_999));
        assertEquals("1:00", Formats.duration(60_000));
        assertEquals("59:59", Formats.duration(3_599_999));
        assertEquals("1:00:00", Formats.duration(3_600_000));
        assertEquals("25:01:02", Formats.duration(90_062_000));
    }
}
