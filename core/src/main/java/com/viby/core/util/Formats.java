package com.viby.core.util;

import java.util.Locale;

public final class Formats {

    private Formats() {
    }

    /** 245000 → "4:05", 3725000 → "1:02:05". */
    public static String duration(long ms) {
        if (ms <= 0) {
            return "0:00";
        }
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    /** 1536 → "1,5 КБ", 734003200 → "700 МБ" (binary units, Russian labels). */
    public static String bytes(long bytes) {
        if (bytes < 1024) {
            return Math.max(0, bytes) + " Б";
        }
        String[] units = {"КБ", "МБ", "ГБ", "ТБ"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        String number = value >= 100 || value == Math.floor(value)
                ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.1f", value).replace('.', ',');
        return number + " " + units[unit];
    }
}
