package com.viby.app

import com.viby.app.model.Playlist
import com.viby.app.model.Track
import com.viby.app.model.TrackFilter
import com.viby.app.state.FavoritesState
import com.viby.app.state.HistoryState
import com.viby.app.state.LibraryIndex
import com.viby.core.settings.Settings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/** Stage 4: the library regrouped, favourites and the listening history. */
class LibraryIndexTest {

    /** A file in a playlist folder of the music folder, with this system's separators. */
    private fun path(folder: String, name: String): String = File(File(File("music").absoluteFile, folder), name).path

    private val drive = Track("1", "Night Drive", "Kavinsky - Topic", 1, videoId = "a", filePath = path("Synth", "Night Drive.mp3"))
    private val driveCopy = drive.copy(id = "2", filePath = path("favs", "Night Drive.mp3"))
    private val odd = Track("3", "Odd Look", "Kavinsky", 1, videoId = "b", filePath = path("Synth", "Odd Look.mp3"))
    private val vevo = Track("4", "Lose Yourself", "EminemVEVO", 1, videoId = "c", filePath = path("favs", "Lose Yourself.mp3"))
    private val local = Track("5", "Demo", "", 1, filePath = path("Импорт", "demo.mp3"))
    private val pending = Track("6", "Soon", "Kavinsky", 1, videoId = "d", downloadProgress = 10)

    private val synth = Playlist("Synth", "Synth", listOf(drive, odd, pending))
    private val favs = Playlist("favs", "favs", listOf(driveCopy, vevo, local))
    private val albums = mapOf(
        drive.filePath!! to "OutRun", driveCopy.filePath!! to "OutRun", odd.filePath!! to "OutRun",
        vevo.filePath!! to "8 Mile",
    )
    private val index = LibraryIndex(listOf(synth, favs), albums)

    @Test
    fun everySongIsListedOnceWithItsPlaylists() {
        assertEquals(listOf("Night Drive", "Odd Look", "Soon", "Lose Yourself", "Demo"), index.tracks.map { it.track.title })
        assertEquals(listOf("Synth", "favs"), index.tracks.first().playlists.map { it.name })
    }

    @Test
    fun artistsMergeTopicAndVevoChannels() {
        assertEquals(listOf("Eminem", "Kavinsky", LibraryIndex.UNKNOWN_ARTIST), index.artists.map { it.name })
        assertEquals(3, index.artist("kavinsky")!!.tracks.size)
    }

    @Test
    fun albumsComeFromFileTags() {
        assertEquals(listOf("8 Mile", "OutRun"), index.albums.map { it.name })
        val outrun = index.albums.last()
        assertEquals("Kavinsky", outrun.detail)
        assertEquals(listOf("Night Drive", "Odd Look"), outrun.tracks.map { it.track.title })
    }

    @Test
    fun foldersGroupDownloadedFiles() {
        assertEquals(listOf("favs", "Synth", "Импорт"), index.folders.map { it.name })
        // the copy of "Night Drive" in favs is the same song: it is counted where its first copy lies
        assertEquals(listOf("Lose Yourself"), index.folders.first().tracks.map { it.track.title })
    }

    @Test
    fun songKeySurvivesMovingTheMusicFolder() {
        val moved = local.copy(filePath = File(File(File("elsewhere").absoluteFile, "Импорт"), "demo.mp3").path)
        assertEquals(local.key, moved.key)
        assertEquals(drive.key, driveCopy.key)
        assertEquals("yt:a", drive.key)
    }

    @Test
    fun favouritesAndHistoryAreKeptBySong(@TempDir temp: Path) {
        val file = temp.resolve("settings.json")
        val favorites = FavoritesState(Settings(file, temp))
        favorites.toggle(drive)
        favorites.set(listOf(vevo, local), true)
        assertTrue(favorites.isFavorite(driveCopy)) // the copy in another playlist is the same song
        favorites.toggle(vevo)
        assertFalse(favorites.isFavorite(vevo))

        val history = HistoryState(Settings(file, temp))
        history.record(odd)
        Thread.sleep(5)
        history.record(drive)
        assertEquals(listOf(drive.key, odd.key), history.recent)

        // both survive a restart
        val restarted = Settings(file, temp)
        assertEquals(setOf(drive.key, local.key), FavoritesState(restarted).liked.keys)
        assertEquals(listOf(drive.key, odd.key), HistoryState(restarted).recent)
        assertNull(index.find("yt:missing"))
    }

    @Test
    fun missingFileFilterShowsDownloadedTracksWithoutAFile() {
        val lost = drive.copy(fileMissing = true)
        assertEquals(listOf(lost), listOf(drive, lost, pending).filter(TrackFilter.MISSING::matches))
        assertEquals(listOf(pending), listOf(drive, lost, pending).filter(TrackFilter.NOT_DOWNLOADED::matches))
    }
}
