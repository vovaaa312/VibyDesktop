package com.viby.core.tools;

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** A concrete downloadable release of a {@link Tool} for the current platform. */
public record ToolPackage(Tool tool, String version, List<Artifact> artifacts) {

    public enum Kind { RAW, ZIP, TAR_XZ }

    /**
     * One file to download. {@code entries} maps a file name inside the archive (or the
     * download itself for RAW) to the installed executable name.
     */
    public record Artifact(URI url, @Nullable String sha256, Kind kind,
                           Map<String, String> entries) {
    }
}
