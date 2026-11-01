package com.viby.core.ytdlp;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

/** Locations of the external executables yt-dlp needs. */
public interface ToolPaths {

    Path ytDlp();

    /** Directory containing ffmpeg and ffprobe, or null to let yt-dlp search PATH. */
    @Nullable Path ffmpegDirectory();

    /** Deno executable used by yt-dlp-ejs for YouTube, or null if unavailable. */
    @Nullable Path deno();
}
