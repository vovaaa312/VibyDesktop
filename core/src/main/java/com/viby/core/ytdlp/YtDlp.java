package com.viby.core.ytdlp;

import org.jspecify.annotations.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the standalone yt-dlp executable. Mirrors the youtubedl-android YoutubeDL API
 * (execute/getInfo/destroyProcessById) so ported code keeps its structure.
 */
public final class YtDlp {

    public interface ProgressCallback {
        void onProgress(float progress, long etaSeconds, String line);
    }

    private static final Pattern PROGRESS = Pattern.compile(
            "\\[download]\\s+(\\d{1,3}(?:\\.\\d+)?)%.*?ETA\\s+(?:(\\d+):)?(\\d+):(\\d+)");

    /** Browser cookies could not be read; the request was retried without them. */
    public interface CookieFailureListener {
        void onCookieFailure(String message);
    }

    private final ToolPaths tools;
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Set<String> canceled = ConcurrentHashMap.newKeySet();
    private volatile @Nullable CookieFailureListener cookieFailureListener;

    public YtDlp(ToolPaths tools) {
        this.tools = tools;
    }

    public void setCookieFailureListener(@Nullable CookieFailureListener listener) {
        this.cookieFailureListener = listener;
    }

    public YtDlpResponse execute(YtDlpRequest request) throws YtDlpException, InterruptedException {
        return execute(request, null, null);
    }

    /**
     * Runs the request. When {@code --cookies-from-browser} fails (Chrome locks and encrypts
     * its cookie database on Windows), the request is repeated without cookies so public
     * playlists and videos keep working.
     */
    public YtDlpResponse execute(YtDlpRequest request, @Nullable String processId,
                                 @Nullable ProgressCallback callback)
            throws YtDlpException, InterruptedException {
        try {
            return run(request, processId, callback);
        } catch (YtDlpException e) {
            if (e instanceof YtDlpException.Canceled || !request.hasOption("--cookies-from-browser")
                    || !isCookieLoadError(e.getMessage())) {
                throw e;
            }
            CookieFailureListener listener = cookieFailureListener;
            if (listener != null) {
                listener.onCookieFailure(e.getMessage());
            }
            return run(request.copy().removeOption("--cookies-from-browser"), processId, callback);
        }
    }

    static boolean isCookieLoadError(@Nullable String message) {
        if (message == null) {
            return false;
        }
        String text = message.toLowerCase(java.util.Locale.ROOT);
        return text.contains("cookie database") || text.contains("failed to decrypt")
                || text.contains("dpapi") || text.contains("failed to load cookies")
                || (text.contains("could not find") && text.contains("cookies"));
    }

    private YtDlpResponse run(YtDlpRequest request, @Nullable String processId,
                              @Nullable ProgressCallback callback)
            throws YtDlpException, InterruptedException {
        YtDlpRequest effective = request.copy();
        if (callback != null) {
            effective.addOption("--newline");
        }
        List<String> command = command(effective);
        long started = System.currentTimeMillis();
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.environment().put("PYTHONUTF8", "1");
            process = builder.start();
        } catch (IOException e) {
            throw new YtDlpException("Unable to start yt-dlp: " + e.getMessage(), e);
        }
        if (processId != null) {
            if (processes.putIfAbsent(processId, process) != null) {
                process.destroyForcibly();
                throw new YtDlpException("Process ID already exists: " + processId);
            }
        }
        StringBuilder err = new StringBuilder();
        Thread errReader = new Thread(() -> readLines(process.getErrorStream(), err, null),
                "yt-dlp-stderr");
        errReader.setDaemon(true);
        errReader.start();
        StringBuilder out = new StringBuilder();
        try {
            readLines(process.getInputStream(), out, callback);
            int exitCode = process.waitFor();
            errReader.join();
            if (processId != null && canceled.remove(processId)) {
                throw new YtDlpException.Canceled(processId);
            }
            if (exitCode != 0) {
                String message = err.toString().trim();
                throw new YtDlpException(message.isEmpty()
                        ? "yt-dlp exited with code " + exitCode : message);
            }
            return new YtDlpResponse(command, exitCode,
                    System.currentTimeMillis() - started, out.toString(), err.toString());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        } finally {
            if (processId != null) {
                processes.remove(processId);
                canceled.remove(processId);
            }
        }
    }

    /** Runs {@code --dump-json} for a single video and parses its metadata. */
    public VideoInfo getInfo(YtDlpRequest request) throws YtDlpException, InterruptedException {
        YtDlpRequest effective = request.copy();
        effective.addOption("--dump-json");
        effective.addOption("--no-warnings");
        String out = execute(effective).out().trim();
        try {
            // a URL resolving to several entries prints one JSON object per line
            int newline = out.indexOf('\n');
            return new VideoInfo(new JSONObject(newline >= 0 ? out.substring(0, newline) : out));
        } catch (JSONException e) {
            throw new YtDlpException("Unable to parse yt-dlp metadata", e);
        }
    }

    public String version() throws YtDlpException, InterruptedException {
        return execute(new YtDlpRequest(List.of()).addOption("--version")).out().trim();
    }

    /** Kills a running process started with {@code processId}; returns false if none is running. */
    public boolean destroyProcessById(String processId) {
        Process process = processes.get(processId);
        if (process == null) {
            return false;
        }
        canceled.add(processId);
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        return true;
    }

    List<String> command(YtDlpRequest request) {
        List<String> command = new ArrayList<>();
        command.add(tools.ytDlp().toString());
        Path ffmpeg = tools.ffmpegDirectory();
        if (ffmpeg != null && !request.hasOption("--ffmpeg-location")) {
            command.add("--ffmpeg-location");
            command.add(ffmpeg.toString());
        }
        Path deno = tools.deno();
        if (deno != null && !request.hasOption("--js-runtimes")) {
            command.add("--js-runtimes");
            command.add("deno:" + deno);
        }
        command.addAll(request.buildCommand());
        return command;
    }

    private static void readLines(InputStream stream, StringBuilder sink,
                                  @Nullable ProgressCallback callback) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (sink) {
                    sink.append(line).append('\n');
                }
                if (callback != null) {
                    reportProgress(line, callback);
                }
            }
        } catch (IOException ignored) {
            // the stream closes when the process is destroyed
        }
    }

    static void reportProgress(String line, ProgressCallback callback) {
        Matcher matcher = PROGRESS.matcher(line);
        if (matcher.find()) {
            float progress = Float.parseFloat(matcher.group(1));
            long hours = matcher.group(2) != null ? Long.parseLong(matcher.group(2)) : 0;
            long eta = hours * 3600 + Long.parseLong(matcher.group(3)) * 60
                    + Long.parseLong(matcher.group(4));
            callback.onProgress(progress, eta, line);
        } else {
            callback.onProgress(0f, -1, line);
        }
    }
}
