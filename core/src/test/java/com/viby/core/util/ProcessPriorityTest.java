package com.viby.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads the real Windows priority class. CI runners are slow (a PowerShell start can take
 * seconds), so the helper processes live long and the check polls instead of sleeping once.
 */
class ProcessPriorityTest {

    private static final long TIMEOUT_MS = 30_000;

    /** A download process and the child it starts later (like yt-dlp → ffmpeg) both end up below normal. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void backgroundLowersTheProcessAndItsChildren() throws Exception {
        // cmd starts ping (the child) right away and keeps running for a minute
        Process parent = new ProcessBuilder("cmd", "/c", "ping -n 60 127.0.0.1 > nul").start();
        try {
            ProcessPriority.background(parent);
            String result = "";
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                List<Long> pids = parent.descendants().map(ProcessHandle::pid).collect(Collectors.toList());
                if (!pids.isEmpty()) {
                    pids.add(0, parent.pid());
                    result = priorityClasses(pids);
                    if (result.equals(String.join(",", pids.stream().map(p -> "BelowNormal").toList()))) {
                        return;
                    }
                }
                Thread.sleep(500);
            }
            assertEquals("BelowNormal for cmd and ping", result);
        } finally {
            parent.descendants().forEach(ProcessHandle::destroyForcibly);
            parent.destroyForcibly();
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void playbackRaisesTheDecoder() throws Exception {
        Process decoder = new ProcessBuilder("ping", "-n", "60", "127.0.0.1").start();
        try {
            ProcessPriority.playback(decoder);
            assertEquals("AboveNormal", priorityClasses(List.of(decoder.pid())));
        } finally {
            decoder.destroyForcibly();
        }
    }

    /** One PowerShell call for all processes: "BelowNormal,BelowNormal". */
    private static String priorityClasses(List<Long> pids) throws Exception {
        String ids = pids.stream().map(String::valueOf).collect(Collectors.joining(","));
        Process query = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                "((" + ids + ") | ForEach-Object { (Get-Process -Id $_).PriorityClass }) -join ','")
                .redirectErrorStream(true).start();
        String out = new String(query.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        query.waitFor();
        return out;
    }
}
