package com.viby.core.tools;

import com.viby.core.platform.Platform;
import com.viby.core.ytdlp.ToolPaths;

import org.jspecify.annotations.Nullable;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads, verifies and updates yt-dlp, ffmpeg and deno inside the app data directory.
 * Replaces the binaries bundled by youtubedl-android on Android.
 */
public final class BinaryManager implements ToolPaths {

    public interface Listener {
        /** {@code totalBytes} is -1 when the server does not report a size. */
        void onProgress(Tool tool, long downloadedBytes, long totalBytes);
    }

    public enum UpdateResult { UP_TO_DATE, UPDATED }

    private static final String VERSIONS_FILE = "versions.json";

    private final Path root;
    private final Platform platform;
    private final ToolResolver resolver;
    private final Http http;
    private final Clock clock;

    public BinaryManager(Path root, Platform platform) {
        this(root, platform, new Http(), Clock.systemUTC());
    }

    private BinaryManager(Path root, Platform platform, Http http, Clock clock) {
        this(root, platform, new ToolResolver(platform, http), http, clock);
    }

    BinaryManager(Path root, Platform platform, ToolResolver resolver, Http http, Clock clock) {
        this.root = root;
        this.platform = platform;
        this.resolver = resolver;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public Path ytDlp() {
        return executable(Tool.YT_DLP, "yt-dlp");
    }

    @Override
    public @Nullable Path ffmpegDirectory() {
        return isInstalled(Tool.FFMPEG) ? root.resolve(Tool.FFMPEG.directoryName) : null;
    }

    @Override
    public @Nullable Path deno() {
        return isInstalled(Tool.DENO) ? executable(Tool.DENO, "deno") : null;
    }

    public Path ffmpeg() {
        return executable(Tool.FFMPEG, "ffmpeg");
    }

    public Path ffprobe() {
        return executable(Tool.FFMPEG, "ffprobe");
    }

    public boolean isInstalled(Tool tool) {
        for (String name : executables(tool)) {
            if (!Files.isRegularFile(executable(tool, name))) {
                return false;
            }
        }
        return true;
    }

    public List<Tool> missing() {
        List<Tool> result = new ArrayList<>();
        for (Tool tool : Tool.values()) {
            if (!isInstalled(tool)) {
                result.add(tool);
            }
        }
        return result;
    }

    public @Nullable String installedVersion(Tool tool) {
        JSONObject entry = readVersions().optJSONObject(tool.directoryName);
        return entry != null && isInstalled(tool) ? entry.optString("version", null) : null;
    }

    /** First-launch setup: installs every tool that is not present yet. */
    public void installMissing(@Nullable Listener listener) throws IOException, InterruptedException {
        for (Tool tool : missing()) {
            install(resolver.latest(tool), listener);
        }
    }

    /** Installs the latest release of {@code tool} unless that version is already present. */
    public synchronized UpdateResult update(Tool tool, @Nullable Listener listener)
            throws IOException, InterruptedException {
        ToolPackage latest = resolver.latest(tool);
        if (latest.version().equals(installedVersion(tool))) {
            remember(tool, latest.version());
            return UpdateResult.UP_TO_DATE;
        }
        install(latest, listener);
        return UpdateResult.UPDATED;
    }

    /**
     * Updates {@code tool} at most once per {@code interval}, so YouTube breakages are fixed
     * without a Viby release. Returns null when the check was not due yet.
     */
    public @Nullable UpdateResult updateIfDue(Tool tool, Duration interval,
                                              @Nullable Listener listener)
            throws IOException, InterruptedException {
        JSONObject entry = readVersions().optJSONObject(tool.directoryName);
        long checkedAt = entry != null ? entry.optLong("checkedAt", 0) : 0;
        if (isInstalled(tool) && clock.millis() - checkedAt < interval.toMillis()) {
            return null;
        }
        return update(tool, listener);
    }

    private synchronized void install(ToolPackage toolPackage, @Nullable Listener listener)
            throws IOException, InterruptedException {
        Tool tool = toolPackage.tool();
        Files.createDirectories(root);
        Path downloads = Files.createTempDirectory(root, "download-" + tool.directoryName);
        Path staging = root.resolve(tool.directoryName + ".new");
        try {
            Archives.deleteRecursively(staging);
            Files.createDirectories(staging);
            int index = 0;
            for (ToolPackage.Artifact artifact : toolPackage.artifacts()) {
                Path file = downloads.resolve("artifact" + index++);
                http.download(artifact.url(), file, artifact.sha256(), listener == null ? null
                        : (done, total) -> listener.onProgress(tool, done, total));
                Archives.extract(file, artifact.kind(), artifact.entries(), staging);
            }
            Path target = root.resolve(tool.directoryName);
            Archives.deleteRecursively(target);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            remember(tool, toolPackage.version());
        } finally {
            Archives.deleteRecursively(downloads);
            Archives.deleteRecursively(staging);
        }
    }

    private List<String> executables(Tool tool) {
        return switch (tool) {
            case YT_DLP -> List.of("yt-dlp");
            case FFMPEG -> List.of("ffmpeg", "ffprobe");
            case DENO -> List.of("deno");
        };
    }

    private Path executable(Tool tool, String name) {
        return root.resolve(tool.directoryName).resolve(name + platform.executableSuffix());
    }

    private synchronized void remember(Tool tool, String version) throws IOException {
        JSONObject versions = readVersions();
        versions.put(tool.directoryName, new JSONObject()
                .put("version", version)
                .put("checkedAt", clock.millis()));
        Files.writeString(root.resolve(VERSIONS_FILE), versions.toString(2),
                StandardCharsets.UTF_8);
    }

    private synchronized JSONObject readVersions() {
        Path file = root.resolve(VERSIONS_FILE);
        try {
            return Files.isRegularFile(file)
                    ? new JSONObject(Files.readString(file, StandardCharsets.UTF_8))
                    : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
