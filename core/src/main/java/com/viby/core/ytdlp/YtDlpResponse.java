package com.viby.core.ytdlp;

import java.util.List;

/** Result of a finished yt-dlp process. */
public record YtDlpResponse(List<String> command, int exitCode, long elapsedMs,
                            String out, String err) {
}
