package com.viby.core.update;

import com.viby.core.platform.Platform;
import com.viby.core.settings.Settings;
import com.viby.core.tools.GithubReleases;
import com.viby.core.tools.Http;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

/**
 * Desktop AppUpdateManager: checks the latest GitHub release of VibyDesktop at most once a day,
 * downloads the installer for this OS with SHA-256 verification and launches it.
 */
public final class AppUpdater {

    public static final String REPOSITORY = "vovaaa312/VibyDesktop";
    private static final String KEY_LAST_CHECK = "appUpdateLastCheck";
    private static final Duration AUTO_CHECK_INTERVAL = Duration.ofDays(1);

    public record Update(String version, GithubReleases.Asset installer, String pageUrl) {
    }

    public interface ReleaseSource {
        GithubReleases.@Nullable Release latest() throws IOException, InterruptedException;
    }

    private final String currentVersion;
    private final Platform platform;
    private final Settings settings;
    private final ReleaseSource releases;
    private final Http http;
    private final Path downloads;
    private final Clock clock;

    public AppUpdater(String currentVersion, Platform platform, Settings settings, Path downloads) {
        this(currentVersion, platform, settings, new Http(), downloads, Clock.systemUTC());
    }

    private AppUpdater(String currentVersion, Platform platform, Settings settings, Http http,
                       Path downloads, Clock clock) {
        this(currentVersion, platform, settings, () -> latestOrNull(http), http, downloads, clock);
    }

    AppUpdater(String currentVersion, Platform platform, Settings settings, ReleaseSource releases,
               Http http, Path downloads, Clock clock) {
        this.currentVersion = currentVersion;
        this.platform = platform;
        this.settings = settings;
        this.releases = releases;
        this.http = http;
        this.downloads = downloads;
        this.clock = clock;
    }

    public String currentVersion() {
        return currentVersion;
    }

    /** Daily automatic check; null when not due, up to date or nothing to install. */
    public @Nullable Update checkIfDue() throws IOException, InterruptedException {
        long last = Long.parseLong(settings.getString(KEY_LAST_CHECK, "0"));
        if (clock.millis() - last < AUTO_CHECK_INTERVAL.toMillis()) {
            return null;
        }
        return check();
    }

    /** Manual check; also resets the daily timer, so "later" postpones the prompt for a day. */
    public @Nullable Update check() throws IOException, InterruptedException {
        GithubReleases.Release release = releases.latest();
        settings.putString(KEY_LAST_CHECK, String.valueOf(clock.millis()));
        if (release == null || !ReleaseVersion.isNewer(release.tag(), currentVersion)) {
            return null;
        }
        GithubReleases.Asset installer = installerFor(release);
        if (installer == null) {
            return null;
        }
        return new Update(release.tag(), installer,
                "https://github.com/" + REPOSITORY + "/releases/tag/" + release.tag());
    }

    /** Downloads and verifies the installer; returns the local file to open. */
    public Path download(Update update, Http.@Nullable Progress progress) throws IOException, InterruptedException {
        Files.createDirectories(downloads);
        Path target = downloads.resolve(update.installer().name());
        http.download(update.installer().url(), target, update.installer().sha256(), progress);
        return target;
    }

    /** Starts the installer; the caller exits so files can be replaced. */
    public void launchInstaller(Path installer) throws IOException {
        String file = installer.toAbsolutePath().toString();
        ProcessBuilder builder = switch (platform.os()) {
            case WINDOWS -> new ProcessBuilder("msiexec", "/i", file);
            case MAC -> new ProcessBuilder("open", file);
            case LINUX -> new ProcessBuilder("xdg-open", file);
        };
        builder.start();
    }

    GithubReleases.@Nullable Asset installerFor(GithubReleases.Release release) {
        String extension = switch (platform.os()) {
            case WINDOWS -> ".msi";
            case MAC -> ".dmg";
            case LINUX -> ".deb";
        };
        for (GithubReleases.Asset asset : release.assets()) {
            if (asset.name().toLowerCase(Locale.ROOT).endsWith(extension)) {
                return asset;
            }
        }
        return null;
    }

    /** A repository without releases answers 404; that is "no update", not an error. */
    private static GithubReleases.@Nullable Release latestOrNull(Http http) throws IOException, InterruptedException {
        try {
            return new GithubReleases(http).latest(REPOSITORY);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("HTTP 404")) {
                return null;
            }
            throw e;
        }
    }
}
