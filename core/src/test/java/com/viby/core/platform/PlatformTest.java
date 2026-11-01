package com.viby.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlatformTest {

    @Test
    void parsesOsNames() {
        assertEquals(Platform.Os.WINDOWS, Platform.parseOs("Windows 10"));
        assertEquals(Platform.Os.MAC, Platform.parseOs("Mac OS X"));
        assertEquals(Platform.Os.LINUX, Platform.parseOs("Linux"));
    }

    @Test
    void parsesArchNames() {
        assertEquals(Platform.Arch.X64, Platform.parseArch("amd64"));
        assertEquals(Platform.Arch.ARM64, Platform.parseArch("aarch64"));
        assertEquals(Platform.Arch.ARM64, Platform.parseArch("arm64"));
    }

    @Test
    void executableSuffixOnlyOnWindows() {
        assertEquals(".exe",
                new Platform(Platform.Os.WINDOWS, Platform.Arch.X64).executableSuffix());
        assertEquals("", new Platform(Platform.Os.MAC, Platform.Arch.ARM64).executableSuffix());
    }
}
