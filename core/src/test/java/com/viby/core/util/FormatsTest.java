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

    @Test
    void bytesUseBinaryUnits() {
        assertEquals("512 Б", Formats.bytes(512));
        assertEquals("1,5 КБ", Formats.bytes(1536));
        assertEquals("700 МБ", Formats.bytes(700L << 20));
        assertEquals("2 ГБ", Formats.bytes(2L << 30));
        assertEquals("1,3 ГБ", Formats.bytes(1_400_000_000L));
    }
}
