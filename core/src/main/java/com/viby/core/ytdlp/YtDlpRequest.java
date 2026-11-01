package com.viby.core.ytdlp;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Command-line options and URLs for a single yt-dlp invocation (desktop YoutubeDLRequest). */
public final class YtDlpRequest {

    private final List<String> urls = new ArrayList<>();
    private final Map<String, List<String>> options = new LinkedHashMap<>();

    public YtDlpRequest(String url) {
        urls.add(url);
    }

    public YtDlpRequest(List<String> urls) {
        this.urls.addAll(urls);
    }

    public YtDlpRequest addOption(String name) {
        options.computeIfAbsent(name, key -> new ArrayList<>());
        return this;
    }

    public YtDlpRequest addOption(String name, String value) {
        options.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        return this;
    }

    public boolean hasOption(String name) {
        return options.containsKey(name);
    }

    /** First value of an option, or null when it is absent or a flag without value. */
    public @Nullable String getOption(String name) {
        List<String> values = options.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public List<String> getOptionValues(String name) {
        List<String> values = options.get(name);
        return values == null ? List.of() : Collections.unmodifiableList(values);
    }

    public List<String> getUrls() {
        return Collections.unmodifiableList(urls);
    }

    /** Options followed by URLs, without the executable itself. */
    public List<String> buildCommand() {
        List<String> command = new ArrayList<>();
        for (Map.Entry<String, List<String>> option : options.entrySet()) {
            if (option.getValue().isEmpty()) {
                command.add(option.getKey());
                continue;
            }
            for (String value : option.getValue()) {
                command.add(option.getKey());
                command.add(value);
            }
        }
        command.addAll(urls);
        return command;
    }

    YtDlpRequest copy() {
        YtDlpRequest copy = new YtDlpRequest(urls);
        options.forEach((name, values) -> copy.options.put(name, new ArrayList<>(values)));
        return copy;
    }
}
