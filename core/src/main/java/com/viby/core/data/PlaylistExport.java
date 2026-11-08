package com.viby.core.data;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Playlists as extended M3U8 for other players (foobar2000, VLC, AIMP, phones). */
public final class PlaylistExport {

    private PlaylistExport() {
    }

    /**
     * Writes the downloaded tracks in playlist order; paths are relative to the playlist file when
     * it is saved next to the music (portable), absolute otherwise.
     *
     * @return how many tracks were written
     */
    public static int writeM3u(List<Track> tracks, Path target) throws IOException {
        Path base = target.toAbsolutePath().getParent();
        StringBuilder text = new StringBuilder("#EXTM3U\n");
        int written = 0;
        for (Track track : tracks) {
            if (!track.downloaded || track.filePath == null || !new File(track.filePath).isFile()) {
                continue;
            }
            long seconds = track.durationMs > 0 ? Math.round(track.durationMs / 1000.0) : -1;
            String title = track.uploader != null && !track.uploader.isEmpty()
                    && !track.title.contains(track.uploader)
                    ? track.uploader + " - " + track.title : track.title;
            text.append("#EXTINF:").append(seconds).append(',')
                    .append(title.replace('\n', ' ')).append('\n')
                    .append(path(base, Path.of(track.filePath))).append('\n');
            written++;
        }
        Files.writeString(target, text, StandardCharsets.UTF_8);
        return written;
    }

    static String path(Path base, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (base != null && absolute.startsWith(base.normalize())) {
            return base.normalize().relativize(absolute).toString().replace(File.separatorChar, '/');
        }
        return absolute.toString();
    }
}
