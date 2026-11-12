package com.viby.core.data;

import com.viby.core.settings.Settings;
import com.viby.core.util.StorageHelper;

import org.jspecify.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Library backup shared with the Android app: playlists, their order and YouTube links as one
 * JSON document ({@value #FORMAT}), or a zip holding that document plus the audio files.
 * File paths are stored relative to the music folder, so a backup restores on another computer;
 * cookies, account tokens and machine paths are never written.
 */
public final class LibraryBackup {

    public static final String FORMAT = "viby-library";
    public static final int VERSION = 1;
    static final String ZIP_DOCUMENT = "viby-library.json";
    static final String ZIP_MUSIC = "music/";
    /** Settings that describe taste, not this machine (no folders, cookies or accounts). */
    static final List<String> PORTABLE_SETTINGS = List.of("audioFormat", "audioQuality", "theme", "equalizer",
            "favorites", "playlistViews");
    /** Restored into what is already there instead of replacing it, like the playlists. */
    static final List<String> MERGED_SETTINGS = List.of("favorites", "playlistViews");
    private static final java.util.regex.Pattern FORBIDDEN =
            java.util.regex.Pattern.compile("[\\\\:*?\"<>|\\x00-\\x1f]|[. ]$");

    /**
     * @param missing restored tracks without a file; the ones with a video id can be downloaded
     */
    public record ImportResult(int playlists, int tracks, int skipped, boolean settingsRestored,
                               List<Track> missing) {
    }

    public interface Progress {
        void onProgress(int done, int total);
    }

    private final LibraryRepository library;
    private final Settings settings;
    private final Supplier<Path> musicDirectory;

    public LibraryBackup(LibraryRepository library, Settings settings, Supplier<Path> musicDirectory) {
        this.library = library;
        this.settings = settings;
        this.musicDirectory = musicDirectory;
    }

    // ------------------------------------------------------------------ export

    /**
     * @param playlists names to export, or null for the whole library (with settings)
     * @param withMusic writes a zip with the audio files next to the document
     */
    public void export(Path target, @Nullable Collection<String> playlists, boolean withMusic,
                       Progress progress) throws IOException {
        Path root = musicDirectory.get();
        JSONObject document = document(root, playlists);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            if (withMusic) {
                writeZip(temp, document, root, progress);
            } else {
                Files.writeString(temp, document.toString(2), StandardCharsets.UTF_8);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    JSONObject document(Path root, @Nullable Collection<String> only) {
        Map<String, List<Track>> byPlaylist = new LinkedHashMap<>();
        for (Track track : library.allTracks()) {
            byPlaylist.computeIfAbsent(track.playlistName, key -> new ArrayList<>()).add(track);
        }
        JSONArray playlists = new JSONArray();
        for (Map.Entry<String, Long> entry : library.playlistCreatedAt().entrySet()) {
            String name = entry.getKey();
            if (only != null && !only.contains(name)) {
                continue;
            }
            JSONArray tracks = new JSONArray();
            for (Track track : byPlaylist.getOrDefault(name, List.of())) {
                tracks.put(trackJson(track, root));
            }
            JSONObject playlist = new JSONObject()
                    .put("name", name)
                    .put("createdAt", entry.getValue())
                    .put("tracks", tracks);
            PlaylistSource source = library.source(name);
            if (source != null) {
                playlist.put("source", new JSONObject()
                        .put("sourceUrl", source.sourceUrl)
                        .putOpt("youtubePlaylistId", source.youtubePlaylistId)
                        .put("youtubeSyncEnabled", source.youtubeSyncEnabled)
                        .putOpt("youtubeOwnerChannelId", source.youtubeOwnerChannelId));
            }
            playlists.put(playlist);
        }
        JSONObject document = new JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("exportedAt", System.currentTimeMillis())
                .put("app", "VibyDesktop")
                .put("playlists", playlists);
        if (only == null) {
            JSONObject portable = new JSONObject();
            for (String key : PORTABLE_SETTINGS) {
                JSONObject json = settings.getJson(key);
                if (!json.isEmpty()) {
                    portable.put(key, json);
                } else {
                    String value = settings.getString(key, "");
                    if (!value.isEmpty()) {
                        portable.put(key, value);
                    }
                }
            }
            document.put("settings", portable);
        }
        return document;
    }

    private static JSONObject trackJson(Track track, Path root) {
        JSONObject json = new JSONObject()
                .put("title", track.title)
                .putOpt("videoId", track.videoId)
                .putOpt("uploader", track.uploader)
                .put("durationMs", track.durationMs)
                .putOpt("thumbnailUrl", track.thumbnailUrl)
                .put("position", track.position)
                .putOpt("youtubePosition", track.youtubePosition)
                .putOpt("youtubePlaylistItemId", track.youtubePlaylistItemId)
                .put("createdAt", track.createdAt)
                .putOpt("sourceUrl", track.sourceUrl)
                .put("downloaded", track.downloaded);
        if (track.downloaded && track.filePath != null && !track.filePath.isEmpty()) {
            json.put("file", relative(root, Path.of(track.filePath)));
        }
        return json;
    }

    /** "playlist/name.mp3" under the music folder; files elsewhere keep only their name. */
    static String relative(Path root, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path base = root.toAbsolutePath().normalize();
        Path relative = absolute.startsWith(base) ? base.relativize(absolute) : absolute.getFileName();
        return relative.toString().replace(File.separatorChar, '/');
    }

    private void writeZip(Path target, JSONObject document, Path root, Progress progress) throws IOException {
        Set<String> names = new HashSet<>();
        List<String> entries = new ArrayList<>();
        for (Object playlist : document.getJSONArray("playlists")) {
            for (Object track : ((JSONObject) playlist).getJSONArray("tracks")) {
                String file = ((JSONObject) track).optString("file", "");
                if (!file.isEmpty() && names.add(file) && Files.isRegularFile(root.resolve(file))) {
                    entries.add(file);
                }
            }
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            zip.putNextEntry(new ZipEntry(ZIP_DOCUMENT));
            zip.write(document.toString(2).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            // audio is already compressed: store it to save time
            zip.setLevel(0);
            int done = 0;
            for (String file : entries) {
                zip.putNextEntry(new ZipEntry(ZIP_MUSIC + file));
                Files.copy(root.resolve(file), zip);
                zip.closeEntry();
                progress.onProgress(++done, entries.size());
            }
        }
    }

    // ------------------------------------------------------------------ import

    /** Merges a backup (JSON or zip) into the library; existing playlists and tracks are kept. */
    public ImportResult importFrom(Path source, Progress progress) throws IOException {
        Path root = musicDirectory.get();
        JSONObject document = isZip(source) ? readZip(source, root, progress)
                : new JSONObject(Files.readString(source, StandardCharsets.UTF_8));
        if (!FORMAT.equals(document.optString("format"))) {
            throw new IOException("Это не резервная копия Viby");
        }
        if (document.optInt("version") > VERSION) {
            throw new IOException("Резервная копия создана более новой версией Viby — обновите приложение");
        }
        return merge(document, root);
    }

    ImportResult merge(JSONObject document, Path root) {
        int playlists = 0;
        int added = 0;
        int skipped = 0;
        List<Track> missing = new ArrayList<>();
        Set<String> existingPlaylists = library.playlistCreatedAt().keySet();
        for (Object item : document.optJSONArray("playlists", new JSONArray())) {
            JSONObject playlist = (JSONObject) item;
            String name = playlist.optString("name", "").trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!existingPlaylists.contains(name)) {
                playlists++;
            }
            library.ensurePlaylist(name, playlist.optLong("createdAt", System.currentTimeMillis()));
            restoreSource(name, playlist.optJSONObject("source"));

            List<Track> current = library.tracks(name);
            Set<String> videoIds = new HashSet<>();
            Set<String> fileNames = new HashSet<>();
            for (Track track : current) {
                if (track.videoId != null) {
                    videoIds.add(track.videoId);
                }
                if (track.filePath != null && !track.filePath.isEmpty()) {
                    fileNames.add(Path.of(track.filePath).getFileName().toString().toLowerCase());
                }
            }
            for (Object entry : playlist.optJSONArray("tracks", new JSONArray())) {
                JSONObject json = (JSONObject) entry;
                Track track = track(json, name, root);
                String fileName = track.filePath.isEmpty() ? null
                        : Path.of(track.filePath).getFileName().toString().toLowerCase();
                boolean duplicate = track.videoId != null ? !videoIds.add(track.videoId)
                        : fileName != null && !fileNames.add(fileName);
                if (duplicate) {
                    skipped++;
                    continue;
                }
                if (!track.downloaded && track.videoId == null) {
                    // a local file that is gone cannot be restored or downloaded
                    skipped++;
                    continue;
                }
                track.position = 0;
                library.insert(track);
                added++;
                if (!track.downloaded) {
                    missing.add(track);
                }
            }
        }
        boolean settingsRestored = restoreSettings(document.optJSONObject("settings"));
        return new ImportResult(playlists, added, skipped, settingsRestored, missing);
    }

    private static Track track(JSONObject json, String playlist, Path root) {
        Track track = new Track();
        track.playlistName = playlist;
        track.title = json.optString("title", "");
        track.videoId = json.optString("videoId", null);
        track.uploader = json.optString("uploader", null);
        track.durationMs = json.optLong("durationMs");
        track.thumbnailUrl = json.optString("thumbnailUrl", null);
        track.youtubePosition = json.has("youtubePosition") ? json.optInt("youtubePosition") : null;
        track.youtubePlaylistItemId = json.optString("youtubePlaylistItemId", null);
        track.createdAt = json.optLong("createdAt", System.currentTimeMillis());
        track.sourceUrl = json.optString("sourceUrl", null);
        if (track.sourceUrl == null && track.videoId != null) {
            track.sourceUrl = "https://www.youtube.com/watch?v=" + track.videoId;
        }
        String file = json.optString("file", "");
        Path path = file.isEmpty() ? null : resolveInside(root, file);
        if (path != null && Files.isRegularFile(path)) {
            track.filePath = path.toString();
            track.downloaded = true;
            track.downloadState = Track.DOWNLOAD_DOWNLOADED;
        } else {
            // the file name is kept so a later folder scan can still relink it
            track.filePath = path != null ? path.toString() : "";
            track.downloaded = false;
            track.downloadState = Track.DOWNLOAD_WAITING;
        }
        return track;
    }

    private void restoreSource(String playlist, @Nullable JSONObject json) {
        if (json == null || library.source(playlist) != null) {
            return;
        }
        PlaylistSource source = new PlaylistSource();
        source.playlistName = playlist;
        source.sourceUrl = json.optString("sourceUrl", "");
        source.updatedAt = System.currentTimeMillis();
        source.youtubePlaylistId = json.optString("youtubePlaylistId", null);
        source.youtubeSyncEnabled = json.optBoolean("youtubeSyncEnabled");
        source.youtubeOwnerChannelId = json.optString("youtubeOwnerChannelId", null);
        if (!source.sourceUrl.isEmpty()) {
            library.saveSource(source);
        }
    }

    private boolean restoreSettings(@Nullable JSONObject json) {
        if (json == null || json.isEmpty()) {
            return false;
        }
        for (String key : PORTABLE_SETTINGS) {
            Object value = json.opt(key);
            if (value instanceof JSONObject object) {
                if (MERGED_SETTINGS.contains(key)) {
                    JSONObject merged = new JSONObject(object.toString());
                    JSONObject current = settings.getJson(key);
                    for (String name : current.keySet()) {
                        merged.put(name, current.get(name));
                    }
                    object = merged;
                }
                settings.putJson(key, object);
            } else if (value instanceof String text) {
                settings.putString(key, text);
            }
        }
        return true;
    }

    private static boolean isZip(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] magic = input.readNBytes(2);
            return magic.length == 2 && magic[0] == 'P' && magic[1] == 'K';
        }
    }

    /** Extracts the audio into the music folder (existing files are kept) and returns the document. */
    private static JSONObject readZip(Path source, Path root, Progress progress) throws IOException {
        JSONObject document = null;
        int done = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(source)))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (entry.getName().equals(ZIP_DOCUMENT)) {
                    document = new JSONObject(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                } else if (entry.getName().startsWith(ZIP_MUSIC)) {
                    Path target = resolveInside(root, entry.getName().substring(ZIP_MUSIC.length()));
                    if (target != null && !Files.exists(target)) {
                        Files.createDirectories(target.getParent());
                        try (OutputStream output = Files.newOutputStream(target)) {
                            zip.transferTo(output);
                        }
                    }
                    progress.onProgress(++done, 0);
                }
            }
        }
        if (document == null) {
            throw new IOException("В архиве нет " + ZIP_DOCUMENT);
        }
        return document;
    }

    /** A relative backup path under the music folder; null for paths that escape it. */
    static @Nullable Path resolveInside(Path root, String relative) {
        Path base = root.toAbsolutePath().normalize();
        String[] parts = relative.replace('\\', '/').split("/");
        Path result = base;
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return null;
            }
            // names written by another OS may contain characters this file system rejects
            // (only then: sanitize also shortens long names, which would break file matching)
            result = result.resolve(FORBIDDEN.matcher(part).find() ? StorageHelper.sanitize(part) : part);
        }
        return result.normalize().startsWith(base) ? result.normalize() : null;
    }
}
