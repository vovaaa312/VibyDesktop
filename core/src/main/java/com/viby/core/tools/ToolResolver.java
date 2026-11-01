package com.viby.core.tools;

import com.viby.core.platform.Platform;
import com.viby.core.tools.GithubReleases.Asset;
import com.viby.core.tools.GithubReleases.Release;
import com.viby.core.tools.ToolPackage.Artifact;
import com.viby.core.tools.ToolPackage.Kind;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Finds the latest download of each {@link Tool} for a platform. */
public class ToolResolver {

    static final String YT_DLP_REPO = "yt-dlp/yt-dlp";
    static final String FFMPEG_REPO = "yt-dlp/FFmpeg-Builds";
    static final String DENO_REPO = "denoland/deno";
    /** yt-dlp/FFmpeg-Builds has no macOS builds; this mirror publishes static ones with .sha256. */
    static final String MAC_FFMPEG = "https://ffmpeg.martin-riedl.de/redirect/latest/macos/%s/release/%s.zip";

    public interface ReleaseSource {
        Release latest(String repository) throws IOException, InterruptedException;
    }

    private final Platform platform;
    private final ReleaseSource releases;
    private final Http http;

    public ToolResolver(Platform platform, Http http) {
        this(platform, new GithubReleases(http)::latest, http);
    }

    ToolResolver(Platform platform, ReleaseSource releases, Http http) {
        this.platform = platform;
        this.releases = releases;
        this.http = http;
    }

    public ToolPackage latest(Tool tool) throws IOException, InterruptedException {
        return switch (tool) {
            case YT_DLP -> ytDlp();
            case FFMPEG -> platform.os() == Platform.Os.MAC ? macFfmpeg() : ffmpeg();
            case DENO -> deno();
        };
    }

    private ToolPackage ytDlp() throws IOException, InterruptedException {
        Release release = releases.latest(YT_DLP_REPO);
        Asset asset = require(release, ytDlpAssetName(platform));
        return new ToolPackage(Tool.YT_DLP, release.tag(), List.of(new Artifact(
                asset.url(), asset.sha256(), Kind.RAW,
                Map.of(asset.name(), "yt-dlp" + platform.executableSuffix()))));
    }

    private ToolPackage ffmpeg() throws IOException, InterruptedException {
        Release release = releases.latest(FFMPEG_REPO);
        Asset asset = require(release, ffmpegAssetName(platform));
        String exe = platform.executableSuffix();
        // the "latest" tag is moving; the digest identifies the actual build
        String version = asset.sha256() != null ? asset.sha256().substring(0, 12) : release.tag();
        return new ToolPackage(Tool.FFMPEG, version, List.of(new Artifact(asset.url(),
                asset.sha256(), platform.os() == Platform.Os.WINDOWS ? Kind.ZIP : Kind.TAR_XZ,
                Map.of("ffmpeg" + exe, "ffmpeg" + exe, "ffprobe" + exe, "ffprobe" + exe))));
    }

    private ToolPackage macFfmpeg() throws IOException, InterruptedException {
        String arch = platform.arch() == Platform.Arch.ARM64 ? "arm64" : "amd64";
        List<Artifact> artifacts = new ArrayList<>();
        String version = "";
        for (String name : List.of("ffmpeg", "ffprobe")) {
            URI url = http.resolve(URI.create(String.format(MAC_FFMPEG, arch, name)));
            String sha = http.getString(URI.create(url + ".sha256")).trim().split("\\s+")[0];
            artifacts.add(new Artifact(url, sha, Kind.ZIP, Map.of(name, name)));
            if (version.isEmpty()) {
                // .../download/macos/arm64/<build>_<version>/ffmpeg.zip
                String path = url.getPath();
                version = path.substring(0, path.lastIndexOf('/'));
                version = version.substring(version.lastIndexOf('/') + 1);
            }
        }
        return new ToolPackage(Tool.FFMPEG, version, artifacts);
    }

    private ToolPackage deno() throws IOException, InterruptedException {
        Release release = releases.latest(DENO_REPO);
        Asset asset = require(release, denoAssetName(platform));
        String exe = "deno" + platform.executableSuffix();
        return new ToolPackage(Tool.DENO, release.tag(), List.of(
                new Artifact(asset.url(), asset.sha256(), Kind.ZIP, Map.of(exe, exe))));
    }

    static String ytDlpAssetName(Platform platform) {
        boolean arm = platform.arch() == Platform.Arch.ARM64;
        return switch (platform.os()) {
            case WINDOWS -> arm ? "yt-dlp_arm64.exe" : "yt-dlp.exe";
            case MAC -> "yt-dlp_macos";
            case LINUX -> arm ? "yt-dlp_linux_aarch64" : "yt-dlp_linux";
        };
    }

    static String ffmpegAssetName(Platform platform) {
        boolean arm = platform.arch() == Platform.Arch.ARM64;
        return switch (platform.os()) {
            case WINDOWS -> "ffmpeg-master-latest-" + (arm ? "winarm64" : "win64") + "-gpl.zip";
            case LINUX -> "ffmpeg-master-latest-" + (arm ? "linuxarm64" : "linux64")
                    + "-gpl.tar.xz";
            case MAC -> throw new IllegalArgumentException("macOS uses the static mirror");
        };
    }

    static String denoAssetName(Platform platform) {
        String arch = platform.arch() == Platform.Arch.ARM64 ? "aarch64" : "x86_64";
        String target = switch (platform.os()) {
            case WINDOWS -> "pc-windows-msvc";
            case MAC -> "apple-darwin";
            case LINUX -> "unknown-linux-gnu";
        };
        return "deno-" + arch + "-" + target + ".zip";
    }

    private static Asset require(Release release, String name) throws IOException {
        Asset asset = release.asset(name);
        if (asset == null) {
            throw new IOException("Release " + release.tag() + " has no asset " + name);
        }
        return asset;
    }
}
