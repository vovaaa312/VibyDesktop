package com.viby.core.download;

import com.viby.core.ytdlp.YtDlpRequest;

import java.io.File;
import java.util.function.Supplier;

/** Defines yt-dlp/FFmpeg audio post-processing and validates its output. */
final class MediaConverter {

    /** Settings → Качество MP3; "0" (best VBR) unless the user picked a bitrate. */
    private final Supplier<String> mp3Quality;

    MediaConverter() {
        this(() -> "0");
    }

    MediaConverter(Supplier<String> mp3Quality) {
        this.mp3Quality = mp3Quality;
    }

    void configure(YtDlpRequest request, String format) {
        request.addOption("-x");
        request.addOption("--audio-format", format);
        // m4a is the source stream as it is; a bitrate would only force a re-encode
        request.addOption("--audio-quality", "mp3".equals(format) ? mp3Quality.get() : "0");
        request.addOption("--embed-thumbnail");
        request.addOption("--embed-metadata");
    }

    File findOutput(File directory, String baseName, String format) {
        File expected = new File(directory, baseName + "." + format);
        if (expected.isFile()) {
            return expected;
        }
        File[] candidates = directory.listFiles((dir, name) -> {
            File candidate = new File(dir, name);
            int dot = name.lastIndexOf('.');
            return candidate.isFile()
                    && dot > 0
                    && name.substring(0, dot).equals(baseName);
        });
        if (candidates != null && candidates.length > 0) {
            return candidates[0];
        }
        throw new IllegalStateException(
                "Файл не найден после конвертации: " + baseName);
    }
}
