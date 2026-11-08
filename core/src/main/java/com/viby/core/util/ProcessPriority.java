package com.viby.core.util;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CPU priority of helper processes. Downloads (yt-dlp, the ffmpeg it starts for conversion,
 * deno) run below normal so music playback and games keep the CPU when they need it; the
 * playback decoder runs above normal so the sound does not break up while something else is
 * busy. Best effort: a failure only logs.
 */
public final class ProcessPriority {

    private static final Logger LOG = Logger.getLogger(ProcessPriority.class.getName());
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private static final int PROCESS_SET_INFORMATION = 0x0200;
    private static final int BELOW_NORMAL_PRIORITY_CLASS = 0x4000;
    private static final int ABOVE_NORMAL_PRIORITY_CLASS = 0x8000;
    /** How often a background tree is checked for new children (yt-dlp starts ffmpeg late). */
    private static final long TREE_POLL_MS = 1_000;

    private ProcessPriority() {
    }

    /**
     * Lowers {@code process} and every child it starts while it runs. Windows children inherit
     * the class, but the yt-dlp launcher may start its interpreter before the class is set, so
     * the tree is re-checked until the process exits.
     */
    public static void background(Process process) {
        if (!set(process.pid(), false)) {
            return;
        }
        Thread.ofVirtual().name("priority-" + process.pid()).start(() -> {
            Set<Long> done = new HashSet<>();
            done.add(process.pid());
            try {
                while (process.isAlive()) {
                    process.descendants().forEach(child -> {
                        if (done.add(child.pid())) {
                            set(child.pid(), false);
                        }
                    });
                    Thread.sleep(TREE_POLL_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Raises the playback decoder above normal. */
    public static void playback(Process process) {
        set(process.pid(), true);
    }

    private static boolean set(long pid, boolean high) {
        try {
            if (WINDOWS) {
                Kernel32 kernel = Kernel32.INSTANCE;
                if (kernel == null) {
                    return false;
                }
                Pointer handle = kernel.OpenProcess(PROCESS_SET_INFORMATION, false, (int) pid);
                if (handle == null) {
                    return false;
                }
                try {
                    return kernel.SetPriorityClass(handle,
                            high ? ABOVE_NORMAL_PRIORITY_CLASS : BELOW_NORMAL_PRIORITY_CLASS);
                } finally {
                    kernel.CloseHandle(handle);
                }
            }
            // raising needs root on macOS/Linux; lowering (positive nice) is always allowed
            if (high) {
                return false;
            }
            Process renice = new ProcessBuilder("renice", "-n", "10", "-p", String.valueOf(pid))
                    .redirectErrorStream(true).start();
            renice.getInputStream().readAllBytes();
            return renice.waitFor() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable e) {
            LOG.log(Level.FINE, "Could not change priority of " + pid, e);
            return false;
        }
    }

    private interface Kernel32 extends Library {
        @Nullable Kernel32 INSTANCE = load();

        private static @Nullable Kernel32 load() {
            try {
                return Native.load("kernel32", Kernel32.class);
            } catch (Throwable e) {
                LOG.log(Level.WARNING, "kernel32 is not available", e);
                return null;
            }
        }

        @Nullable Pointer OpenProcess(int desiredAccess, boolean inheritHandle, int processId);

        boolean SetPriorityClass(Pointer process, int priorityClass);

        boolean CloseHandle(Pointer handle);
    }
}
