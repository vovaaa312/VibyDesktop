package com.viby.core.platform;

import java.util.Locale;

/** Host OS and CPU architecture, used to pick the right yt-dlp/ffmpeg/deno binaries. */
public final class Platform {

    public enum Os { WINDOWS, MAC, LINUX }

    public enum Arch { X64, ARM64 }

    private final Os os;
    private final Arch arch;

    public Platform(Os os, Arch arch) {
        this.os = os;
        this.arch = arch;
    }

    public static Platform current() {
        return new Platform(parseOs(System.getProperty("os.name", "")),
                parseArch(System.getProperty("os.arch", "")));
    }

    static Os parseOs(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        if (value.startsWith("windows")) {
            return Os.WINDOWS;
        }
        if (value.startsWith("mac") || value.startsWith("darwin")) {
            return Os.MAC;
        }
        return Os.LINUX;
    }

    static Arch parseArch(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        return value.equals("aarch64") || value.equals("arm64") ? Arch.ARM64 : Arch.X64;
    }

    public Os os() {
        return os;
    }

    public Arch arch() {
        return arch;
    }

    /** File name suffix for native executables on this OS. */
    public String executableSuffix() {
        return os == Os.WINDOWS ? ".exe" : "";
    }

    @Override
    public String toString() {
        return os + "/" + arch;
    }
}
