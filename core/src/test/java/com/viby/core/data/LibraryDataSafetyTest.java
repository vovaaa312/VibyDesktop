package com.viby.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.viby.core.settings.Settings;
import com.viby.core.util.StorageHelper;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stage 3 of the guideline: relinking, backups, M3U export and the storage report. */
class LibraryDataSafetyTest {

    @TempDir
    Path temp;

    private Database database;
    private final java.util.List<Database> extra = new java.util.ArrayList<>();
    private LibraryRepository library;
    private Settings settings;
    private Path music;
    /** Tags the fake ffprobe reports, by file name. */
    private final Map<String, AudioProbe.Info> tags = new HashMap<>();
    private int probes;

    @BeforeEach
    void setUp() throws Exception {
        database = Database.open(temp.resolve("viby.db"));
        library = new LibraryRepository(database);
        music = Files.createDirectories(temp.resolve("Music"));
        settings = new Settings(temp.resolve("settings.json"), music);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
        for (Database other : extra) {
            other.close();
        }
    }

    private LibraryScanner scanner() {
        return new LibraryScanner(library, () -> music, file -> {
            probes++;
            return tags.getOrDefault(file.getName(), AudioProbe.Info.EMPTY);
        });
    }

    // ---------------------------------------------------------------- relinking

    @Test
    void movedMusicFolderIsRelinkedByFileNameWithoutProbing() throws Exception {
        Track track = library.insert(track("Drive", "a", temp.resolve("old/Drive/Song.mp3").toString()));
        Path moved = file("Drive/Song.mp3");

        LibraryScanner.Result result = scanner().scan(false, (done, total) -> { });

        assertEquals(1, result.relinked());
        assertTrue(result.missing().isEmpty());
        assertEquals(moved.toAbsolutePath().toString(), library.track(track.id).filePath);
        assertEquals(0, probes);
    }

    @Test
    void renamedFileIsFoundByTheYoutubeIdInItsTags() throws Exception {
        Track track = library.insert(track("Drive", "abc", temp.resolve("gone.mp3").toString()));
        Path renamed = file("Drive/Other name.mp3");
        tags.put("Other name.mp3", new AudioProbe.Info("Song", "Artist", 1000, "abc"));

        LibraryScanner.Result result = scanner().scan(false, (done, total) -> { });

        assertEquals(1, result.relinked());
        assertEquals(renamed.toAbsolutePath().toString(), library.track(track.id).filePath);
    }

    @Test
    void untrackedFilesJoinThePlaylistOfTheirFolder() throws Exception {
        library.createPlaylist("Радио: Untrust Us");
        file("Радио_ Untrust Us/one.mp3");
        file("New folder/two.m4a");
        file("New folder/two.m4a.part");
        tags.put("one.mp3", new AudioProbe.Info("One", "Band", 2000, "vid1"));

        LibraryScanner.Result result = scanner().scan(true, (done, total) -> { });

        assertEquals(2, result.adopted());
        Track one = library.tracks("Радио: Untrust Us").get(0);
        assertEquals("One", one.title);
        assertEquals("vid1", one.videoId);
        assertEquals("https://www.youtube.com/watch?v=vid1", one.sourceUrl);
        assertEquals("two", library.tracks("New folder").get(0).title);
    }

    @Test
    void filesStillMissingAreReported() throws Exception {
        library.insert(track("Drive", "x", temp.resolve("nowhere.mp3").toString()));
        LibraryScanner.Result result = scanner().scan(false, (done, total) -> { });
        assertEquals(1, result.missing().size());
        assertEquals(0, result.relinked());
    }

    // ------------------------------------------------------------------ backups

    @Test
    void backupRoundTripRestoresPlaylistsOrderSourcesAndSettings() throws Exception {
        library.ensurePlaylist("Drive", 1234L);
        Path song = file("Drive/Song.mp3");
        library.insert(track("Drive", "a", song.toString()));
        library.insert(track("Drive", "b", null));
        PlaylistSource source = new PlaylistSource();
        source.playlistName = "Drive";
        source.sourceUrl = "https://www.youtube.com/playlist?list=PL1";
        source.youtubeSyncEnabled = true;
        library.saveSource(source);
        settings.putString("theme", "SPOTIFY");
        settings.setCookieSource(com.viby.core.auth.CookieSource.browser("firefox", null));
        Path backup = temp.resolve("backup.json");
        new LibraryBackup(library, settings, () -> music).export(backup, null, false, (d, t) -> { });

        String text = Files.readString(backup);
        assertFalse(text.contains("firefox"), "cookie settings must not leave the machine");
        assertFalse(text.contains(temp.toString().replace("\\", "\\\\")), "no absolute paths");

        LibraryRepository restored = new LibraryRepository(open(temp.resolve("restored.db")));
        Settings fresh = new Settings(temp.resolve("fresh.json"), music);
        LibraryBackup.ImportResult result =
                new LibraryBackup(restored, fresh, () -> music).importFrom(backup, (d, t) -> { });

        assertEquals(1, result.playlists());
        assertEquals(2, result.tracks());
        assertTrue(result.settingsRestored());
        assertEquals("SPOTIFY", fresh.getString("theme", ""));
        assertEquals(1234L, restored.playlistCreatedAt().get("Drive"));
        List<Track> tracks = restored.tracks("Drive");
        assertEquals(List.of("a", "b"), tracks.stream().map(t -> t.videoId).toList());
        assertTrue(tracks.get(0).downloaded);
        assertEquals(song.toAbsolutePath().normalize().toString(), tracks.get(0).filePath);
        assertFalse(tracks.get(1).downloaded);
        assertEquals("https://www.youtube.com/watch?v=b", tracks.get(1).sourceUrl);
        assertEquals(1, result.missing().size());
        assertTrue(restored.source("Drive").youtubeSyncEnabled);
    }

    @Test
    void importMergesWithoutDuplicates() throws Exception {
        library.insert(track("Drive", "a", null));
        Path backup = temp.resolve("drive.json");
        LibraryBackup backups = new LibraryBackup(library, settings, () -> music);
        backups.export(backup, List.of("Drive"), false, (d, t) -> { });

        LibraryBackup.ImportResult result = backups.importFrom(backup, (d, t) -> { });

        assertEquals(0, result.tracks());
        assertEquals(1, result.skipped());
        assertFalse(result.settingsRestored(), "a playlist export carries no settings");
        assertEquals(1, library.tracks("Drive").size());
    }

    @Test
    void zipBackupCarriesTheMusic() throws Exception {
        Path song = file("Drive/Song.mp3");
        library.insert(track("Drive", "a", song.toString()));
        Path backup = temp.resolve("backup.zip");
        new LibraryBackup(library, settings, () -> music).export(backup, null, true, (d, t) -> { });

        Path otherMusic = Files.createDirectories(temp.resolve("Other"));
        LibraryRepository restored = new LibraryRepository(open(temp.resolve("zip.db")));
        new LibraryBackup(restored, new Settings(temp.resolve("z.json"), otherMusic), () -> otherMusic)
                .importFrom(backup, (d, t) -> { });

        Track track = restored.tracks("Drive").get(0);
        assertTrue(track.downloaded);
        assertEquals("audio Drive/Song.mp3", Files.readString(Path.of(track.filePath)));
    }

    @Test
    void foreignDocumentsAndEscapingPathsAreRejected() throws Exception {
        Path other = Files.writeString(temp.resolve("other.json"), "{\"format\":\"something\"}");
        LibraryBackup backups = new LibraryBackup(library, settings, () -> music);
        assertThrows(IOException.class, () -> backups.importFrom(other, (d, t) -> { }));
        assertNull(LibraryBackup.resolveInside(music, "../evil.mp3"));
        assertNull(LibraryBackup.resolveInside(music, "a/../../evil.mp3"));
        assertEquals(music.resolve("Радио_ X").resolve("a.mp3").toAbsolutePath().normalize(),
                LibraryBackup.resolveInside(music, "Радио: X/a.mp3"));
    }

    // ------------------------------------------------------- export and storage

    @Test
    void m3uListsDownloadedTracksWithRelativePaths() throws Exception {
        Path song = file("Drive/Song.mp3");
        Track downloaded = track("Drive", "a", song.toString());
        downloaded.uploader = "Band";
        Path target = music.resolve("Drive.m3u8");

        int written = PlaylistExport.writeM3u(List.of(downloaded, track("Drive", "b", null)), target);

        assertEquals(1, written);
        assertEquals("#EXTM3U\n#EXTINF:180,Band - Title a\nDrive/Song.mp3\n", Files.readString(target));
    }

    @Test
    void storageReportFindsLeftoversAndCleansThem() throws Exception {
        Path song = file("Drive/Song.mp3");
        library.insert(track("Drive", "a", song.toString()));
        library.insert(track("Chill", "a", song.toString()));
        file("Drive/Unused.mp3");
        file("Drive/Next.mp3.part");
        file("Busy/Running.webm.part");
        LibraryStorage storage = new LibraryStorage(library, () -> music);

        LibraryStorage.Report report = storage.report();

        assertEquals(1, report.libraryFiles(), "a shared file counts once");
        assertEquals(1, report.unreferenced().size());
        assertEquals(2, report.temp().size());
        assertTrue(report.freeBytes() > 0);

        storage.deleteTemp(List.of("Busy"));
        assertFalse(Files.exists(music.resolve("Drive/Next.mp3.part")));
        assertTrue(Files.exists(music.resolve("Busy/Running.webm.part")), "a paused download resumes from it");
        storage.deleteUnreferenced();
        assertFalse(Files.exists(music.resolve("Drive/Unused.mp3")));
        assertTrue(Files.exists(song));
    }

    @Test
    void downloadsNeedFreeSpaceForTheirLength() {
        assertEquals(StorageHelper.MIN_FREE_BYTES + 180 * 48_000L, StorageHelper.requiredBytes(180_000));
        assertTrue(LibraryScanner.isTemp("song.f251.webm"));
        assertTrue(LibraryScanner.isAudio("Song.MP3"));
        assertFalse(LibraryScanner.isAudio("Song.mp3.part"));
    }

    @Test
    void probeReadsTheYoutubeIdFromTags() {
        AudioProbe.Info info = AudioProbe.parse("{\"format\":{\"duration\":\"188.5\",\"tags\":{\"TITLE\":\"Char\","
                + "\"artist\":\"Crystal Castles\",\"purl\":\"https://www.youtube.com/watch?v=KLBj26tRFS8\"}}}");
        assertEquals(new AudioProbe.Info("Char", "Crystal Castles", 188_500, "KLBj26tRFS8"), info);
    }

    private Database open(Path file) throws Exception {
        Database opened = Database.open(file);
        extra.add(opened);
        return opened;
    }

    // ---------------------------------------------------------------- changing the music folder

    @Test
    void changingTheFolderMovesTheLibraryAndKeepsPlaylistFolders() throws Exception {
        Path song = file("Drive/Song.mp3");
        Path shared = file("Drive/Shared.mp3");
        Track a = library.insert(track("Drive", "a", song.toString()));
        Track b = library.insert(track("Drive", "b", shared.toString()));
        // "send to playlist" shares one file between two playlists
        Track c = library.insert(track("Night", "b", shared.toString()));
        Track elsewhere = library.insert(track("Drive", "x", Files.writeString(temp.resolve("other.mp3"), "x").toString()));
        Path target = temp.resolve("NewMusic");

        LibraryMover mover = new LibraryMover(library);
        assertEquals(2, mover.plan(music, target).files());
        LibraryMover.Result result = mover.move(music, target, (done, total) -> { });

        assertEquals(2, result.moved());
        assertEquals(0, result.failed());
        assertEquals(target.resolve("Drive/Song.mp3").toString(), library.track(a.id).filePath);
        assertEquals(target.resolve("Drive/Shared.mp3").toString(), library.track(b.id).filePath);
        assertEquals(library.track(b.id).filePath, library.track(c.id).filePath);
        assertTrue(Files.isRegularFile(target.resolve("Drive/Song.mp3")));
        assertFalse(Files.exists(song));
        // the emptied playlist folder and music folder are removed, files outside are untouched
        assertFalse(Files.exists(music));
        assertEquals(temp.resolve("other.mp3").toString(), library.track(elsewhere.id).filePath);
    }

    /** D:\Music → D:\Music\Viby: what already lies in the new folder stays where it is. */
    @Test
    void movingIntoASubfolderLeavesFilesThatAreAlreadyThere() throws Exception {
        Track outer = library.insert(track("Drive", "a", file("Drive/Song.mp3").toString()));
        Track inner = library.insert(track("Night", "b", file("Viby/Night/Moon.mp3").toString()));
        Path target = music.resolve("Viby");

        LibraryMover mover = new LibraryMover(library);
        assertEquals(1, mover.plan(music, target).files());
        mover.move(music, target, (done, total) -> { });

        assertEquals(target.resolve("Drive/Song.mp3").toString(), library.track(outer.id).filePath);
        assertEquals(target.resolve("Night/Moon.mp3").toString(), library.track(inner.id).filePath);
        assertTrue(Files.isRegularFile(target.resolve("Night/Moon.mp3")));
    }

    @Test
    void moveKeepsADifferentFileThatAlreadyHasTheName() throws Exception {
        Track track = library.insert(track("Drive", "a", file("Drive/Song.mp3").toString()));
        Path target = temp.resolve("NewMusic");
        Files.createDirectories(target.resolve("Drive"));
        Files.writeString(target.resolve("Drive/Song.mp3"), "another recording with this name");

        new LibraryMover(library).move(music, target, (done, total) -> { });

        assertEquals(target.resolve("Drive/Song (2).mp3").toString(), library.track(track.id).filePath);
        assertEquals("another recording with this name", Files.readString(target.resolve("Drive/Song.mp3")));
    }

    @Test
    void moveReusesTheSameFileCopiedByHand() throws Exception {
        Path old = file("Drive/Song.mp3");
        Track track = library.insert(track("Drive", "a", old.toString()));
        Path target = temp.resolve("NewMusic");
        Files.createDirectories(target.resolve("Drive"));
        Files.copy(old, target.resolve("Drive/Song.mp3"));

        LibraryMover.Result result = new LibraryMover(library).move(music, target, (done, total) -> { });

        assertEquals(1, result.moved());
        assertEquals(target.resolve("Drive/Song.mp3").toString(), library.track(track.id).filePath);
        assertFalse(Files.exists(old));
        assertFalse(Files.exists(target.resolve("Drive/Song (2).mp3")));
    }

    /** The user copied the music to the new folder and then pointed Viby at it. */
    @Test
    void scanAfterFolderChangeSwitchesToTheCopiesInsteadOfAddingThemTwice() throws Exception {
        Path oldFile = Files.createDirectories(temp.resolve("OldMusic/Drive")).resolve("Song.mp3");
        Files.writeString(oldFile, "audio Drive/Song.mp3");
        Track track = library.insert(track("Drive", "a", oldFile.toString()));
        Path copy = file("Drive/Song.mp3");

        LibraryScanner.Result result = scanner().scan(true, true, (done, total) -> { });

        assertEquals(1, result.relinked());
        assertEquals(0, result.adopted());
        assertEquals(copy.toAbsolutePath().toString(), library.track(track.id).filePath);
        assertEquals(1, library.tracks("Drive").size());
    }


    // ---------------------------------------------------------------- albums

    @Test
    void albumsAreReadOnceAndAgainOnlyWhenTheFileChanges() throws Exception {
        Path song = file("Drive/Song.mp3");
        Path plain = file("Drive/Plain.mp3");
        tags.put("Song.mp3", new AudioProbe.Info("Song", "Artist", 1000, "a", "Night Drive"));
        Path cache = temp.resolve("albums.json");
        AudioProbe.Reader reader = f -> {
            probes++;
            return tags.getOrDefault(f.getName(), AudioProbe.Info.EMPTY);
        };
        List<String> files = List.of(song.toString(), plain.toString(), temp.resolve("gone.mp3").toString());

        AlbumIndex index = new AlbumIndex(cache, reader);
        assertEquals(2, index.refresh(files, (done, total) -> { }));
        assertEquals(Map.of(song.toString(), "Night Drive"), index.albums());
        assertEquals(0, index.refresh(files, (done, total) -> { }));

        // a restart reads the cache, a changed file is probed again
        AlbumIndex restarted = new AlbumIndex(cache, reader);
        probes = 0;
        assertEquals(0, restarted.refresh(files, (done, total) -> { }));
        assertEquals("Night Drive", restarted.albumOf(song.toString()));
        Files.writeString(song, "re-downloaded, another size");
        tags.put("Song.mp3", new AudioProbe.Info("Song", "Artist", 1000, "a", "Deluxe"));
        assertEquals(1, restarted.refresh(files, (done, total) -> { }));
        assertEquals("Deluxe", restarted.albumOf(song.toString()));
        assertEquals(1, probes);

        // files that left the library are forgotten
        restarted.refresh(List.of(plain.toString()), (done, total) -> { });
        assertNull(restarted.albumOf(song.toString()));
    }

    private Path file(String relative) throws IOException {
        Path file = music.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "audio " + relative);
    }

    private static Track track(String playlist, String videoId, String filePath) {
        Track track = new Track();
        track.playlistName = playlist;
        track.videoId = videoId;
        track.title = "Title " + videoId;
        track.durationMs = 180_000;
        track.filePath = filePath != null ? filePath : "";
        track.downloaded = filePath != null;
        track.sourceUrl = "https://www.youtube.com/watch?v=" + videoId;
        return track;
    }
}
