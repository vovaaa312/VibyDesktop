package com.viby.core.platform;


import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Per-user application directories. {@code -Dviby.home=<dir>} overrides the root (dev/tests). */
public final class AppDirs {

    private final Path root;
    private final Path userHome;

    public AppDirs(Path root, Path userHome) {
        this.root = root;
        this.userHome = userHome;
    }

    public static AppDirs current() {
        Path home = Path.of(System.getProperty("user.home"));
        String override = System.getProperty("viby.home");
        Path root = override != null && !override.isBlank()
                ? Path.of(override)
                : defaultRoot(Platform.current(), System.getenv(), home);
        return new AppDirs(root, home);
    }

    static Path defaultRoot(Platform platform, Map<String, String> env, Path home) {
        switch (platform.os()) {
            case WINDOWS: {
                String local = env.get("LOCALAPPDATA");
                return local != null && !local.isBlank()
                        ? Path.of(local, "Viby") : home.resolve("AppData/Local/Viby");
            }
            case MAC:
                return home.resolve("Library/Application Support/Viby");
            default: {
                String xdg = env.get("XDG_DATA_HOME");
                return xdg != null && !xdg.isBlank()
                        ? Path.of(xdg, "viby") : home.resolve(".local/share/viby");
            }
        }
    }

    public Path root() {
        return root;
    }

    /** yt-dlp, ffmpeg and deno binaries. */
    public Path tools() {
        return dir(root.resolve("tools"));
    }

    public Path database() {
        return dir(root).resolve("viby.db");
    }

    public Path settingsFile() {
        return dir(root).resolve("settings.json");
    }

    /** Netscape cookies.txt exported for yt-dlp and direct YouTube requests. */
    public Path cookiesFile() {
        return dir(root).resolve("youtube_cookies.txt");
    }

    public Path logs() {
        return dir(root.resolve("logs"));
    }

    /** Default library location: ~/Music/Viby. */
    public Path defaultMusicDirectory() {
        return userHome.resolve("Music").resolve("Viby");
    }

    private static Path dir(Path path) {
        try {
            return Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return root.toString();
    }

}
