package com.viby.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.util.List;

class ProcessPriorityTest {

    /** A download process and the child it starts later (like yt-dlp → ffmpeg) both end up below normal. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void backgroundLowersTheProcessAndItsChildren() throws Exception {
        // cmd starts ping (the child) right away and keeps running for a few seconds
        Process parent = new ProcessBuilder("cmd", "/c", "ping -n 6 127.0.0.1 > nul").start();
        try {
            ProcessPriority.background(parent);
            Thread.sleep(1_500);
            List<ProcessHandle> children = parent.descendants().toList();
            assertFalse(children.isEmpty(), "ping should be running");
            assertEquals("BelowNormal", priorityClass(parent.pid()));
            for (ProcessHandle child : children) {
                assertEquals("BelowNormal", priorityClass(child.pid()));
            }
        } finally {
            parent.descendants().forEach(ProcessHandle::destroyForcibly);
            parent.destroyForcibly();
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void playbackRaisesTheDecoder() throws Exception {
        Process decoder = new ProcessBuilder("ping", "-n", "4", "127.0.0.1").start();
        try {
            ProcessPriority.playback(decoder);
            assertEquals("AboveNormal", priorityClass(decoder.pid()));
        } finally {
            decoder.destroyForcibly();
        }
    }

    private static String priorityClass(long pid) throws Exception {
        Process query = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                "(Get-Process -Id " + pid + ").PriorityClass").redirectErrorStream(true).start();
        String out = new String(query.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        query.waitFor();
        return out;
    }
}
