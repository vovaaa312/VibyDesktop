package com.viby.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

class AppDirsTest {

    private static final Path HOME = Path.of("home");

    @Test
    void usesPlatformConventions() {
        assertEquals(Path.of("C:/Users/u/AppData/Local", "Viby"), AppDirs.defaultRoot(
                platform(Platform.Os.WINDOWS), Map.of("LOCALAPPDATA", "C:/Users/u/AppData/Local"),
                HOME));
        assertEquals(HOME.resolve("Library/Application Support/Viby"),
                AppDirs.defaultRoot(platform(Platform.Os.MAC), Map.of(), HOME));
        assertEquals(HOME.resolve(".local/share/viby"),
                AppDirs.defaultRoot(platform(Platform.Os.LINUX), Map.of(), HOME));
        assertEquals(Path.of("/xdg", "viby"), AppDirs.defaultRoot(
                platform(Platform.Os.LINUX), Map.of("XDG_DATA_HOME", "/xdg"), HOME));
    }

    @Test
    void musicGoesToUserMusicFolder() {
        assertEquals(HOME.resolve("Music").resolve("Viby"),
                new AppDirs(Path.of("root"), HOME).defaultMusicDirectory());
    }

    private static Platform platform(Platform.Os os) {
        return new Platform(os, Platform.Arch.X64);
    }
}
