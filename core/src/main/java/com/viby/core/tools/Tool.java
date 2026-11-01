package com.viby.core.tools;

/** External executables managed by {@link BinaryManager}. */
public enum Tool {
    /** YouTube extractor; updated daily. */
    YT_DLP("yt-dlp"),
    /** ffmpeg + ffprobe: audio conversion, thumbnails, playback decoding. */
    FFMPEG("ffmpeg"),
    /** JavaScript runtime required by yt-dlp-ejs for YouTube. */
    DENO("deno");

    public final String directoryName;

    Tool(String directoryName) {
        this.directoryName = directoryName;
    }
}
