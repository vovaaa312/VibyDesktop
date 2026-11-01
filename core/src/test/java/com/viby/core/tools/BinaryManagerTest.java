package com.viby.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.viby.core.platform.Platform;
import com.viby.core.tools.GithubReleases.Asset;
import com.viby.core.tools.GithubReleases.Release;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class BinaryManagerTest {

    private static final Platform WINDOWS = new Platform(Platform.Os.WINDOWS, Platform.Arch.X64);

    @TempDir
    Path root;

    @Test
    void assetNamesMatchUpstreamReleases() {
        Platform mac = new Platform(Platform.Os.MAC, Platform.Arch.ARM64);
        Platform linuxArm = new Platform(Platform.Os.LINUX, Platform.Arch.ARM64);
        assertEquals("yt-dlp.exe", ToolResolver.ytDlpAssetName(WINDOWS));
        assertEquals("yt-dlp_macos", ToolResolver.ytDlpAssetName(mac));
        assertEquals("yt-dlp_linux_aarch64", ToolResolver.ytDlpAssetName(linuxArm));
        assertEquals("ffmpeg-master-latest-win64-gpl.zip", ToolResolver.ffmpegAssetName(WINDOWS));
        assertEquals("ffmpeg-master-latest-linuxarm64-gpl.tar.xz",
                ToolResolver.ffmpegAssetName(linuxArm));
        assertEquals("deno-x86_64-pc-windows-msvc.zip", ToolResolver.denoAssetName(WINDOWS));
        assertEquals("deno-aarch64-apple-darwin.zip", ToolResolver.denoAssetName(mac));
    }

    @Test
    void parsesGithubReleaseDigests() {
        Release release = GithubReleases.parse(new JSONObject("""
                {"tag_name":"2026.10.26","assets":[
                  {"name":"yt-dlp.exe","browser_download_url":"https://x/yt-dlp.exe",
                   "digest":"sha256:abc"},
                  {"name":"other","browser_download_url":"https://x/other"}]}"""));
        assertEquals("2026.10.26", release.tag());
        assertEquals("abc", release.asset("yt-dlp.exe").sha256());
        assertNull(release.asset("other").sha256());
        assertNull(release.asset("missing"));
    }

    @Test
    void installsVerifiesAndUpdatesOnlyWhenVersionChanges() throws Exception {
        FakeHttp http = new FakeHttp();
        byte[] ytDlp = "yt-dlp-binary".getBytes(StandardCharsets.UTF_8);
        byte[] deno = zip(Map.of("deno.exe", "deno"));
        byte[] ffmpeg = zip(Map.of("ffmpeg-master/bin/ffmpeg.exe", "ff",
                "ffmpeg-master/bin/ffprobe.exe", "probe", "ffmpeg-master/doc/readme.txt", "x"));
        http.files.put("https://x/yt-dlp.exe", ytDlp);
        http.files.put("https://x/deno.zip", deno);
        http.files.put("https://x/ffmpeg.zip", ffmpeg);
        String[] ytDlpTag = {"2026.10.26"};
        ToolResolver resolver = new ToolResolver(WINDOWS, repository -> switch (repository) {
            case ToolResolver.YT_DLP_REPO -> new Release(ytDlpTag[0], List.of(
                    asset("yt-dlp.exe", "https://x/yt-dlp.exe", ytDlp)));
            case ToolResolver.DENO_REPO -> new Release("v2.9.8", List.of(
                    asset("deno-x86_64-pc-windows-msvc.zip", "https://x/deno.zip", deno)));
            default -> new Release("latest", List.of(asset(
                    "ffmpeg-master-latest-win64-gpl.zip", "https://x/ffmpeg.zip", ffmpeg)));
        }, http);
        MutableClock clock = new MutableClock();
        BinaryManager manager = new BinaryManager(root, WINDOWS, resolver, http, clock);

        assertEquals(3, manager.missing().size());
        assertNull(manager.deno());
        manager.installMissing(null);

        assertTrue(manager.missing().isEmpty());
        assertEquals("yt-dlp-binary", Files.readString(manager.ytDlp()));
        assertEquals("probe", Files.readString(manager.ffprobe()));
        assertEquals(root.resolve("ffmpeg"), manager.ffmpegDirectory());
        assertEquals(root.resolve("deno").resolve("deno.exe"), manager.deno());
        assertFalse(Files.exists(root.resolve("ffmpeg").resolve("readme.txt")));
        assertEquals("2026.10.26", manager.installedVersion(Tool.YT_DLP));

        assertNull(manager.updateIfDue(Tool.YT_DLP, Duration.ofDays(1), null));
        clock.advance(Duration.ofDays(2));
        assertEquals(BinaryManager.UpdateResult.UP_TO_DATE,
                manager.updateIfDue(Tool.YT_DLP, Duration.ofDays(1), null));
        ytDlpTag[0] = "2026.11.01";
        assertEquals(BinaryManager.UpdateResult.UPDATED, manager.update(Tool.YT_DLP, null));
        assertEquals("2026.11.01", manager.installedVersion(Tool.YT_DLP));
    }

    @Test
    void checksumMismatchKeepsPreviousInstallation() throws Exception {
        FakeHttp http = new FakeHttp();
        http.files.put("https://x/yt-dlp.exe", "tampered".getBytes(StandardCharsets.UTF_8));
        ToolResolver resolver = new ToolResolver(WINDOWS, repository -> new Release("v1",
                List.of(new Asset("yt-dlp.exe", URI.create("https://x/yt-dlp.exe"), "00"))), http);
        BinaryManager manager = new BinaryManager(root, WINDOWS, resolver, http, Clock.systemUTC());
        assertThrows(IOException.class, () -> manager.update(Tool.YT_DLP, null));
        assertFalse(manager.isInstalled(Tool.YT_DLP));
    }

    private static Asset asset(String name, String url, byte[] content) {
        try {
            return new Asset(name, URI.create(url), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** Serves in-memory files through the real checksum verification of {@link Http}. */
    private static final class FakeHttp extends Http {
        final Map<String, byte[]> files = new HashMap<>();

        @Override
        public void download(URI uri, Path target, String expectedSha256, Progress progress)
                throws IOException {
            byte[] content = files.get(uri.toString());
            try (OutputStream output = Files.newOutputStream(target)) {
                output.write(content);
            }
            String actual;
            try {
                actual = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(content));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            if (expectedSha256 != null && !actual.equals(expectedSha256)) {
                Files.delete(target);
                throw new IOException("Checksum mismatch for " + uri);
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
