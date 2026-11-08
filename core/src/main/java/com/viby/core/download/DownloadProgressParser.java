package com.viby.core.download;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns yt-dlp output into a track progress that moves the whole time. The transfer itself is
 * often a fraction of a second, while reading the video page, solving YouTube's JS challenge,
 * probing thumbnails and converting take most of the time; a bar driven by the transfer alone
 * sits at 0% and then jumps to 100%. Each stage gets a share of the bar instead:
 * preparing 0–15, thumbnail 15–25, transfer 25–85, conversion and tags 85–97. The service
 * sets 100 when the file is done. Progress never moves backwards.
 */
final class DownloadProgressParser {

    static final int PREPARE_END = 15;
    static final int THUMBNAIL_END = 25;
    static final int DOWNLOAD_END = 85;

    private static final Pattern DOWNLOAD_PERCENT = Pattern.compile(
            "(?:\\[download]|download:)\\s*([0-9]{1,3}(?:\\.[0-9]+)?)%",
            Pattern.CASE_INSENSITIVE);

    private DownloadProgressParser() {
    }

    /**
     * @param callbackProgress transfer percent the runner parsed from the line (0 when none)
     * @param outputLine       the yt-dlp output line, or null
     */
    static int resolve(int previous, float callbackProgress, @Nullable String outputLine) {
        previous = clamp(previous);
        float transfer = callbackProgress;
        boolean transferring = callbackProgress > 0;
        if (outputLine != null) {
            Matcher matcher = DOWNLOAD_PERCENT.matcher(outputLine);
            if (matcher.find()) {
                try {
                    transfer = Math.max(transfer, Float.parseFloat(matcher.group(1)));
                    transferring = true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        int resolved = previous;
        if (transferring) {
            float share = Math.min(transfer, 100f) / 100f;
            resolved = THUMBNAIL_END + Math.round(share * (DOWNLOAD_END - THUMBNAIL_END));
        } else if (outputLine != null) {
            resolved = stage(previous, outputLine.toLowerCase(Locale.ROOT));
        }
        return Math.max(previous, clamp(resolved));
    }

    private static int stage(int previous, String line) {
        if (line.startsWith("[extractaudio]") || line.startsWith("[fixup")) {
            return 88;
        }
        if (line.startsWith("[embedthumbnail]")) {
            return 93;
        }
        if (line.startsWith("[metadata]")) {
            return 96;
        }
        if (line.startsWith("[download] destination")) {
            return THUMBNAIL_END;
        }
        if (line.contains("thumbnail")) {
            return creep(previous, PREPARE_END, THUMBNAIL_END, 2);
        }
        if (line.startsWith("[youtube") || line.startsWith("[info]") || line.startsWith("[jsc")) {
            return creep(previous, 0, PREPARE_END, 2);
        }
        return previous;
    }

    /** Each line of a stage moves the bar a little, without leaving the stage's share. */
    private static int creep(int previous, int from, int to, int step) {
        return Math.min(to, Math.max(from, previous) + step);
    }

    private static int clamp(int progress) {
        return Math.max(0, Math.min(100, progress));
    }
}
