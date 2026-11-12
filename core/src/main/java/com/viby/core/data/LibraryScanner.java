package com.viby.core.data;

import com.viby.core.util.StorageHelper;

import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the library's audio files again after a reinstall, a lost database or a moved music
 * folder: tracks whose file is gone are relinked by file name, then by the YouTube id in the
 * file's tags; files nobody references can be added to the playlist named after their folder.
 */
public final class LibraryScanner {

    public static final Set<String> AUDIO_EXTENSIONS =
            Set.of("mp3", "m4a", "aac", "opus", "ogg", "flac", "wav", "webm", "wma");

    /** yt-dlp leftovers: partial downloads, fragments, intermediate files and thumbnails. */
    private static final Pattern TEMP_NAME = Pattern.compile(
            ".*(\\.part|\\.ytdl|\\.part-frag\\d+|\\.temp\\.[a-z0-9]+|\\.f\\d+\\.[a-z0-9]+|\\.webp)$");

    public static final String ROOT_PLAYLIST = "Импорт";

    /**
     * @param missing downloaded tracks whose file is still not found; those with a video id can
     *                be downloaded again
     */
    public record Result(int relinked, int adopted, List<Track> missing) {
    }

    public interface Progress {
        void onProgress(int done, int total);
    }

    private final LibraryRepository library;
    private final Supplier<Path> musicDirectory;
    private final AudioProbe.Reader probe;

    public LibraryScanner(LibraryRepository library, Supplier<Path> musicDirectory, AudioProbe.Reader probe) {
        this.library = library;
        this.musicDirectory = musicDirectory;
        this.probe = probe;
    }

    public Result scan(boolean adoptUntracked, Progress progress) throws IOException {
        return scan(adoptUntracked, false, progress);
    }

    /**
     * @param preferRoot after the music folder changed to one that already holds the music
     *                   (copied by hand): tracks whose file still exists elsewhere switch to the
     *                   same file in the music folder instead of the copy being added twice
     */
    public Result scan(boolean adoptUntracked, boolean preferRoot, Progress progress) throws IOException {
        Path root = musicDirectory.get().toAbsolutePath().normalize();
        List<Path> files = audioFiles(root);
        List<Track> tracks = library.allTracks();

        Set<String> referenced = new HashSet<>();
        List<Track> missing = new ArrayList<>();
        List<Track> outside = new ArrayList<>();
        for (Track track : tracks) {
            // pending placeholders have no path yet; restored backup rows keep their old one
            if (track.filePath == null || track.filePath.isEmpty()) {
                continue;
            }
            if (new File(track.filePath).isFile()) {
                referenced.add(key(Path.of(track.filePath)));
                if (preferRoot && !Path.of(track.filePath).toAbsolutePath().normalize().startsWith(root)) {
                    outside.add(track);
                }
            } else {
                missing.add(track);
            }
        }
        List<Path> free = new ArrayList<>();
        for (Path file : files) {
            if (!referenced.contains(key(file))) {
                free.add(file);
            }
        }

        Map<Long, Track> relinked = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        // 1. same file name, preferring the track's own playlist folder (cheap, no ffprobe)
        for (Track track : missing) {
            Path match = byName(track, free);
            if (match != null) {
                link(track, match, relinked, used);
            }
        }
        // 1b. the same file (name and size) copied into the music folder replaces the outside one
        for (Track track : outside) {
            Path match = byName(track, free);
            if (match != null && sameSize(match, Path.of(track.filePath))) {
                link(track, match, relinked, used);
            }
        }
        // 2. YouTube id from the tags of the files that are still unaccounted for
        boolean needIds = adoptUntracked || missing.stream()
                .anyMatch(t -> !relinked.containsKey(t.id) && t.videoId != null);
        Map<Path, AudioProbe.Info> infos = new HashMap<>();
        if (needIds) {
            List<Path> unknown = free.stream().filter(f -> !used.contains(key(f))).toList();
            int done = 0;
            for (Path file : unknown) {
                infos.put(file, probe.read(file.toFile()));
                progress.onProgress(++done, unknown.size());
            }
            for (Track track : missing) {
                if (relinked.containsKey(track.id) || track.videoId == null) {
                    continue;
                }
                Path match = byVideoId(track, infos);
                if (match != null) {
                    link(track, match, relinked, used);
                }
            }
        }
        library.updateAll(relinked.values());

        int adopted = 0;
        if (adoptUntracked) {
            adopted = adopt(root, free.stream().filter(f -> !used.contains(key(f))).toList(), infos);
        }
        List<Track> stillMissing = missing.stream().filter(t -> !relinked.containsKey(t.id)).toList();
        return new Result(relinked.size(), adopted, stillMissing);
    }

    private static void link(Track track, Path file, Map<Long, Track> relinked, Set<String> used) {
        track.filePath = file.toAbsolutePath().toString();
        track.downloaded = true;
        track.downloadState = Track.DOWNLOAD_DOWNLOADED;
        track.downloadErrorCode = null;
        track.downloadErrorMessage = null;
        track.downloadProgress = 100;
        relinked.put(track.id, track);
        used.add(key(file));
    }

    private static @Nullable Path byName(Track track, List<Path> free) {
        String name = Path.of(track.filePath).getFileName().toString();
        String folder = StorageHelper.sanitize(track.playlistName);
        Path anywhere = null;
        int sameName = 0;
        for (Path file : free) {
            if (!file.getFileName().toString().equalsIgnoreCase(name)) {
                continue;
            }
            if (folderName(file).equalsIgnoreCase(folder)) {
                return file;
            }
            anywhere = file;
            sameName++;
        }
        // a name found in several other folders is ambiguous
        return sameName == 1 ? anywhere : null;
    }

    private static @Nullable Path byVideoId(Track track, Map<Path, AudioProbe.Info> infos) {
        String folder = StorageHelper.sanitize(track.playlistName);
        Path anywhere = null;
        for (Map.Entry<Path, AudioProbe.Info> entry : infos.entrySet()) {
            if (!track.videoId.equals(entry.getValue().videoId())) {
                continue;
            }
            if (folderName(entry.getKey()).equalsIgnoreCase(folder)) {
                return entry.getKey();
            }
            // one file can serve the same video in several playlists
            anywhere = entry.getKey();
        }
        return anywhere;
    }

    /** Untracked files join the playlist of their folder, oldest first (download order). */
    private int adopt(Path root, List<Path> files, Map<Path, AudioProbe.Info> infos) throws IOException {
        Map<String, String> playlistByFolder = new HashMap<>();
        for (String name : library.playlistCreatedAt().keySet()) {
            playlistByFolder.putIfAbsent(StorageHelper.sanitize(name).toLowerCase(Locale.ROOT), name);
        }
        List<Path> ordered = new ArrayList<>(files);
        ordered.sort(Comparator.comparingLong(LibraryScanner::modified));
        for (Path file : ordered) {
            Path parent = file.getParent();
            String playlist = parent.equals(root) ? ROOT_PLAYLIST
                    : playlistByFolder.getOrDefault(folderName(file).toLowerCase(Locale.ROOT), folderName(file));
            library.ensurePlaylist(playlist);
            AudioProbe.Info info = infos.getOrDefault(file, AudioProbe.Info.EMPTY);
            Track track = new Track();
            track.title = info.title() != null ? info.title()
                    : LibraryFiles.stripExtension(file.getFileName().toString());
            track.uploader = info.artist();
            track.durationMs = info.durationMs();
            track.filePath = file.toAbsolutePath().toString();
            track.playlistName = playlist;
            track.createdAt = modified(file);
            if (info.videoId() != null) {
                track.videoId = info.videoId();
                track.sourceUrl = "https://www.youtube.com/watch?v=" + info.videoId();
                track.thumbnailUrl = "https://i.ytimg.com/vi/" + info.videoId() + "/mqdefault.jpg";
            }
            library.insert(track);
        }
        return ordered.size();
    }

    /** Audio files in the music folder and its playlist folders (one level, like downloads). */
    public static List<Path> audioFiles(Path root) throws IOException {
        return files(root).stream().filter(f -> isAudio(f.getFileName().toString())).toList();
    }

    /** Every regular file in the music folder and its playlist folders. */
    static List<Path> files(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root, 2)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    public static boolean isTemp(String name) {
        return TEMP_NAME.matcher(name.toLowerCase(Locale.ROOT)).matches();
    }

    public static boolean isAudio(String name) {
        if (isTemp(name)) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 && AUDIO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String folderName(Path file) {
        Path parent = file.getParent();
        return parent != null && parent.getFileName() != null ? parent.getFileName().toString() : "";
    }

    private static boolean sameSize(Path a, Path b) {
        try {
            return Files.size(a) == Files.size(b);
        } catch (IOException e) {
            return false;
        }
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Path identity; Windows and macOS file systems ignore case. */
    static String key(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        return File.separatorChar == '\\' || System.getProperty("os.name", "").startsWith("Mac")
                ? value.toLowerCase(Locale.ROOT) : value;
    }
}
