package com.viby.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

class LibraryFilesTest {

    @TempDir
    Path temp;

    @Test
    void stripExtensionKeepsNamesWithoutUsableExtension() {
        assertEquals("track", LibraryFiles.stripExtension("track.mp3"));
        assertEquals("archive.tar", LibraryFiles.stripExtension("archive.tar.m4a"));
        assertEquals("track", LibraryFiles.stripExtension("track"));
        assertEquals(".hidden", LibraryFiles.stripExtension(".hidden"));
    }

    @Test
    void durationParsingFallsBackForMissingOrInvalidMetadata() {
        assertEquals(1234L, LibraryFiles.parseDuration("1234"));
        assertEquals(0L, LibraryFiles.parseDuration(null));
        assertEquals(0L, LibraryFiles.parseDuration("unknown"));
        assertEquals(215_433L, LibraryFiles.parseSeconds("215.433000"));
        assertEquals(0L, LibraryFiles.parseSeconds("N/A"));
    }

    @Test
    void sharedAndMissingPathsAreNeverDeleted() {
        assertTrue(LibraryFiles.shouldDeleteFile("C:\\music\\track.m4a", 0));
        assertFalse(LibraryFiles.shouldDeleteFile("C:\\music\\track.m4a", 1));
        assertFalse(LibraryFiles.shouldDeleteFile("", 0));
        assertFalse(LibraryFiles.shouldDeleteFile(null, 0));
    }

    @Test
    void uniqueDestinationUsesSuffixOnlyWhenOriginalExists() throws Exception {
        File directory = temp.toFile();
        File original = new File(directory, "track.mp3");
        assertEquals(original, LibraryFiles.uniqueDestination(directory, "track.mp3", 42L));
        assertTrue(original.createNewFile());
        assertEquals(new File(directory, "42 track.mp3"),
                LibraryFiles.uniqueDestination(directory, "track.mp3", 42L));
    }

    /** Real ffprobe from the installed tools; skipped on machines without them. */
    @Test
    void importCopiesFilesAndReadsTags() throws Exception {
        Path tools = Path.of(String.valueOf(System.getenv("LOCALAPPDATA")), "Viby", "tools", "ffmpeg");
        Path ffmpeg = tools.resolve("ffmpeg.exe");
        assumeTrue(Files.isRegularFile(ffmpeg));
        Path tagged = temp.resolve("in").resolve("tagged.mp3");
        Files.createDirectories(tagged.getParent());
        Process gen = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "sine=duration=1", "-metadata", "title=Песня",
                "-metadata", "artist=Artist", tagged.toString()).inheritIO().start();
        assertTrue(gen.waitFor(30, TimeUnit.SECONDS));
        Path broken = temp.resolve("in").resolve("missing.mp3");

        try (Database database = Database.open(temp.resolve("viby.db"))) {
            RoomTrackDao dao = new RoomTrackDao(database);
            Path music = temp.resolve("music");
            LibraryFiles files = new LibraryFiles(dao, () -> music, () -> tools.resolve("ffprobe.exe"));

            assertEquals(2, files.importFiles(List.of(tagged, broken, tagged), "Local"));
            List<Track> tracks = dao.getPlaylistSync("Local");
            assertEquals(2, tracks.size());
            assertEquals("Песня", tracks.get(0).title);
            assertEquals("Artist", tracks.get(0).uploader);
            assertTrue(tracks.get(0).durationMs > 900, "duration " + tracks.get(0).durationMs);
            assertTrue(tracks.get(0).downloaded);
            // the second copy of the same name got the timestamp prefix
            assertTrue(new File(tracks.get(1).filePath).getName().endsWith(" tagged.mp3"));
            assertEquals(1, tracks.get(1).position);
            assertEquals(2, music.resolve("Local").toFile().list().length);
        }
    }
}
