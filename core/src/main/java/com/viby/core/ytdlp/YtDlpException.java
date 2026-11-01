package com.viby.core.ytdlp;

/** yt-dlp exited with an error; the message contains its stderr for DownloadFailure. */
public class YtDlpException extends Exception {

    public YtDlpException(String message) {
        super(message);
    }

    public YtDlpException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The process was destroyed through {@link YtDlp#destroyProcessById(String)}. */
    public static final class Canceled extends YtDlpException {
        public Canceled(String processId) {
            super("yt-dlp process canceled: " + processId);
        }
    }
}
