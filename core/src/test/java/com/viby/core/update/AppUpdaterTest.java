package com.viby.core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.viby.core.platform.Platform;
import com.viby.core.settings.Settings;
import com.viby.core.tools.GithubReleases;
import com.viby.core.tools.Http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class AppUpdaterTest {

    @TempDir
    Path temp;

    private final GithubReleases.Release release = new GithubReleases.Release("v0.2.0", List.of(
            asset("Viby-0.2.0.dmg"), asset("Viby-0.2.0.msi"), asset("viby_0.2.0_amd64.deb")));

    @Test
    void offersNewerReleaseWithInstallerForThisOs() throws Exception {
        AppUpdater.Update update = updater("0.1.0", Platform.Os.WINDOWS, release, Clock.systemUTC()).check();
        assertNotNull(update);
        assertEquals("v0.2.0", update.version());
        assertEquals("Viby-0.2.0.msi", update.installer().name());
        assertEquals("viby_0.2.0_amd64.deb",
                updater("0.1.0", Platform.Os.LINUX, release, Clock.systemUTC()).check().installer().name());
    }

    @Test
    void ignoresSameOrOlderVersionsAndMissingReleases() throws Exception {
        assertNull(updater("0.2.0", Platform.Os.WINDOWS, release, Clock.systemUTC()).check());
        assertNull(updater("1.0.0", Platform.Os.WINDOWS, release, Clock.systemUTC()).check());
        assertNull(updater("0.1.0", Platform.Os.WINDOWS, null, Clock.systemUTC()).check());
    }

    @Test
    void automaticCheckRunsOncePerDay() throws Exception {
        Settings settings = new Settings(temp.resolve("settings.json"), temp);
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        AppUpdater first = new AppUpdater("0.1.0", new Platform(Platform.Os.WINDOWS, Platform.Arch.X64),
                settings, () -> release, new Http(), temp, Clock.fixed(now, ZoneOffset.UTC));
        assertNotNull(first.checkIfDue());
        assertNull(first.checkIfDue());
        AppUpdater nextDay = new AppUpdater("0.1.0", new Platform(Platform.Os.WINDOWS, Platform.Arch.X64),
                settings, () -> release, new Http(), temp,
                Clock.fixed(now.plus(Duration.ofHours(25)), ZoneOffset.UTC));
        assertNotNull(nextDay.checkIfDue());
    }

    private AppUpdater updater(String version, Platform.Os os, GithubReleases.Release latest, Clock clock) {
        return new AppUpdater(version, new Platform(os, Platform.Arch.X64),
                new Settings(temp.resolve("s-" + os + version + ".json"), temp), () -> latest, new Http(), temp,
                clock);
    }

    private static GithubReleases.Asset asset(String name) {
        return new GithubReleases.Asset(name, URI.create("https://x/" + name), "00");
    }
}
