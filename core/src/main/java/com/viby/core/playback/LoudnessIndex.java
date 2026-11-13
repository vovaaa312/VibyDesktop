package com.viby.core.playback;

import com.viby.core.util.ProcessPriority;

import org.json.JSONObject;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loudness of every library file (guideline stage 5: ReplayGain / normalization). ffmpeg's
 * EBU R128 meter reads a track in about a second; the result is cached by path, size and
 * modification time, so each file is measured once. Playback turns it into a gain that brings
 * the track to {@link #TARGET_LUFS} without letting its peaks clip.
 */
public final class LoudnessIndex {

    /** Spotify and YouTube play at about -14 LUFS. */
    public static final double TARGET_LUFS = -14.0;
    /** Peaks stay below this after the gain (true peak, dBTP). */
    static final double PEAK_CEILING_DB = -1.0;
    static final double MAX_BOOST_DB = 10.0;
    static final double MAX_CUT_DB = -15.0;
    private static final long TIMEOUT_SECONDS = 120;
    private static final int SAVE_EVERY = 20;

    /** Integrated loudness and true peak of a file. */
    public record Loudness(double lufs, double peakDb) {
    }

    public interface Meter {
        /** Null when the file cannot be measured (broken, not audio). */
        @Nullable Loudness measure(File file);
    }

    public interface Progress {
        void onProgress(int done, int total);
    }

    private record Entry(long modified, long size, @Nullable Loudness loudness) {
    }

    private final Path cacheFile;
    private final Meter meter;
    private final Map<String, Entry> entries = new HashMap<>();

    public LoudnessIndex(Path cacheFile, Meter meter) {
        this.cacheFile = cacheFile;
        this.meter = meter;
        load();
    }

    /** Gain in dB for the file, or null when it was not measured yet (or changed since). */
    public synchronized @Nullable Float gainDb(String path) {
        Entry entry = entries.get(path);
        if (entry == null || entry.loudness() == null) {
            return entry == null ? null : 0f;
        }
        File file = new File(path);
        if (file.lastModified() != entry.modified() || file.length() != entry.size()) {
            return null;
        }
        return (float) gain(entry.loudness());
    }

    /** Measures one file now (blocking, ~1 s) unless it is known; returns its gain. */
    public float measure(String path) {
        Float known = gainDb(path);
        if (known != null) {
            return known;
        }
        File file = new File(path);
        long modified = file.lastModified();
        long size = file.length();
        Loudness loudness = meter.measure(file);
        synchronized (this) {
            entries.put(path, new Entry(modified, size, loudness));
            save();
        }
        return loudness == null ? 0f : (float) gain(loudness);
    }

    /**
     * Measures the files that are new or changed and forgets the ones that left the library.
     * Returns how many files were measured.
     */
    public int refresh(Collection<String> files, Progress progress) {
        Set<String> wanted = new HashSet<>(files);
        List<String> stale;
        synchronized (this) {
            stale = wanted.stream().filter(path -> new File(path).isFile() && gainDb(path) == null).toList();
        }
        int done = 0;
        for (String path : stale) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            File file = new File(path);
            long modified = file.lastModified();
            long size = file.length();
            Loudness loudness = meter.measure(file);
            synchronized (this) {
                entries.put(path, new Entry(modified, size, loudness));
                if (++done % SAVE_EVERY == 0) {
                    save();
                }
            }
            progress.onProgress(done, stale.size());
        }
        synchronized (this) {
            boolean pruned = entries.keySet().retainAll(wanted);
            if (done > 0 || pruned) {
                save();
            }
        }
        return done;
    }

    /** Gain to the target loudness, limited so the true peak stays below the ceiling. */
    static double gain(Loudness loudness) {
        double gain = TARGET_LUFS - loudness.lufs();
        gain = Math.min(gain, PEAK_CEILING_DB - loudness.peakDb());
        return Math.max(MAX_CUT_DB, Math.min(MAX_BOOST_DB, gain));
    }

    // ---------------------------------------------------------------- ffmpeg meter

    private static final Pattern INTEGRATED = Pattern.compile("I:\\s+(-?[0-9.]+|-inf)\\s+LUFS");
    private static final Pattern PEAK = Pattern.compile("Peak:\\s+(-?[0-9.]+|-inf)\\s+dBFS");

    /** EBU R128 through ffmpeg at below-normal priority (playback and games come first). */
    public static Meter ffmpeg(Supplier<Path> ffmpeg) {
        return file -> {
            try {
                Process process = new ProcessBuilder(ffmpeg.get().toString(), "-hide_banner", "-nostats",
                        "-nostdin", "-i", file.getAbsolutePath(), "-vn",
                        "-af", "ebur128=peak=true:framelog=quiet", "-f", "null", "-")
                        .redirectErrorStream(true)
                        .start();
                ProcessPriority.background(process);
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return null;
                }
                return process.exitValue() == 0 ? parse(output) : null;
            } catch (IOException | RuntimeException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        };
    }

    /** The summary ffmpeg's ebur128 filter prints at the end. */
    static @Nullable Loudness parse(String output) {
        int summary = output.lastIndexOf("Summary:");
        String text = summary >= 0 ? output.substring(summary) : output;
        Matcher integrated = INTEGRATED.matcher(text);
        if (!integrated.find() || integrated.group(1).equals("-inf")) {
            return null; // silence
        }
        Matcher peak = PEAK.matcher(text);
        double peakDb = peak.find() && !peak.group(1).equals("-inf") ? Double.parseDouble(peak.group(1)) : 0.0;
        return new Loudness(Double.parseDouble(integrated.group(1)), peakDb);
    }

    // ---------------------------------------------------------------- cache file

    private void load() {
        try {
            if (!Files.isRegularFile(cacheFile)) {
                return;
            }
            JSONObject root = new JSONObject(Files.readString(cacheFile, StandardCharsets.UTF_8));
            for (String path : root.keySet()) {
                JSONObject item = root.optJSONObject(path);
                if (item == null) {
                    continue;
                }
                Loudness loudness = item.has("i") ? new Loudness(item.optDouble("i"), item.optDouble("p")) : null;
                entries.put(path, new Entry(item.optLong("m"), item.optLong("s"), loudness));
            }
        } catch (IOException | RuntimeException e) {
            entries.clear(); // a broken cache is simply rebuilt
        }
    }

    private void save() {
        JSONObject root = new JSONObject();
        entries.forEach((path, entry) -> {
            JSONObject item = new JSONObject().put("m", entry.modified()).put("s", entry.size());
            if (entry.loudness() != null) {
                item.put("i", entry.loudness().lufs()).put("p", entry.loudness().peakDb());
            }
            root.put(path, item);
        });
        try {
            Path temp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.createDirectories(cacheFile.toAbsolutePath().getParent());
            Files.writeString(temp, root.toString(), StandardCharsets.UTF_8);
            Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // still works from memory; measured again next time
        }
    }
}
