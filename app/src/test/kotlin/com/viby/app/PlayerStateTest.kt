package com.viby.app

import com.viby.app.model.RepeatMode
import com.viby.app.model.Track
import com.viby.app.state.PlayerState
import com.viby.app.ui.tracksLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerStateTest {

    private val tracks = (1..8).map { Track("t$it", "Track $it", "Artist", 180_000) }

    private fun ids(player: PlayerState) = player.queue.map { it.id }

    @Test
    fun turningShuffleOffRestoresTheOriginalOrder() {
        val player = PlayerState()
        player.playQueue(tracks, tracks[0], "p")
        player.toggleShuffle()
        assertEquals("t1", player.queue.first().id) // current track moves to the start of the order
        assertEquals(tracks.map { it.id }.toSet(), ids(player).toSet())

        player.toggleShuffle()
        assertEquals(tracks.map { it.id }, ids(player))
        assertEquals("t1", player.current?.id)
    }

    @Test
    fun nextFollowsShuffleOrderAndRepeat() {
        val player = PlayerState()
        player.playQueue(tracks, tracks.last(), "p")
        player.next()
        assertFalse(player.isPlaying)
        assertEquals("t8", player.current?.id)

        player.repeat = RepeatMode.ALL
        player.next()
        assertEquals("t1", player.current?.id)

        player.toggleShuffle()
        val order = ids(player)
        player.next()
        assertEquals(order[1], player.current?.id)
    }

    @Test
    fun repeatOneRestartsOnlyOnAutomaticAdvance() {
        val player = PlayerState()
        player.playQueue(tracks, tracks[1], "p")
        player.repeat = RepeatMode.ONE
        player.positionMs = 179_000
        player.next(auto = true)
        assertEquals("t2", player.current?.id)
        assertEquals(0, player.positionMs)
        player.next()
        assertEquals("t3", player.current?.id)
    }

    @Test
    fun previousRestartsTrackAfterThreeSeconds() {
        val player = PlayerState()
        player.playQueue(tracks, tracks[2], "p")
        player.positionMs = 10_000
        player.previous()
        assertEquals("t3", player.current?.id)
        assertEquals(0, player.positionMs)
        player.previous()
        assertEquals("t2", player.current?.id)
    }

    @Test
    fun playingATrackOfTheLoadedPlaylistJumpsWithoutResettingTheQueue() {
        val player = PlayerState()
        player.playQueue(tracks, tracks[0], "p")
        player.toggleShuffle()
        val order = ids(player)
        player.playQueue(tracks, tracks[5], "p")
        assertEquals(order, ids(player))
        assertEquals("t6", player.current?.id)
    }

    @Test
    fun insertAfterCurrentPlaysNextInBothModes() {
        val extra = Track("x", "Extra", "A", 1000)
        val plain = PlayerState()
        plain.playQueue(tracks, tracks[2], "p")
        plain.enqueueNext(listOf(extra))
        assertEquals("x", plain.queue[plain.index + 1].id)
        assertTrue(plain.customized)

        val shuffled = PlayerState()
        shuffled.playQueue(tracks, tracks[2], "p")
        shuffled.toggleShuffle()
        shuffled.enqueueNext(listOf(extra))
        assertEquals("x", shuffled.queue[shuffled.index + 1].id)
        // Android/Media3: the item was appended to the list, so the original order ends with it
        shuffled.toggleShuffle()
        assertEquals(tracks.map { it.id } + "x", ids(shuffled))
    }

    @Test
    fun removingAndMovingKeepTheCurrentTrack() {
        val player = PlayerState()
        player.playQueue(tracks, tracks[3], "p")
        player.removeFromQueue(0)
        assertEquals("t4", player.current?.id)
        player.removeFromQueue(player.index) // the current item cannot be removed
        assertEquals(7, player.queue.size)

        player.moveInQueue(player.queue.lastIndex, 0)
        assertEquals("t8", player.queue.first().id)
        assertEquals("t4", player.current?.id)
        assertTrue(player.customized)
    }

    @Test
    fun resetToPlaylistTurnsShuffleOffAndKeepsTheCurrentTrack() {
        val player = PlayerState()
        player.playQueue(tracks, tracks[4], "p")
        player.toggleShuffle()
        player.enqueue(listOf(Track("x", "Extra", "A", 1000)))
        player.resetTo(tracks, "p")
        assertFalse(player.shuffle)
        assertFalse(player.customized)
        assertEquals(tracks.map { it.id }, ids(player))
        assertEquals("t5", player.current?.id)
    }

    @Test
    fun uneditedQueueFollowsNewDownloadsOfItsPlaylist() {
        val player = PlayerState()
        player.playQueue(tracks.take(3), tracks[0], "p")
        player.syncWithPlaylist("p", tracks.take(5))
        assertEquals(tracks.take(5).map { it.id }, ids(player))

        player.enqueue(listOf(Track("x", "Extra", "A", 1000)))
        player.syncWithPlaylist("p", tracks)
        assertEquals(6, player.queue.size) // a customized queue is left alone
    }

    @Test
    fun savedStateRestoresQueueShuffleOrderAndPosition() {
        val player = PlayerState()
        player.playQueue(tracks, tracks[2], "p")
        player.toggleShuffle()
        player.repeat = RepeatMode.ALL
        player.positionMs = 42_000
        val order = ids(player)
        val saved = player.saveState()

        val restored = PlayerState()
        var loaded: Pair<String, Long>? = null
        restored.loader = { track, start, paused ->
            assertTrue(paused)
            loaded = track.id to start
        }
        restored.restoreState(saved) { id -> tracks.firstOrNull { it.id == id } }
        assertEquals(order, ids(restored))
        assertEquals("t3" to 42_000L, loaded)
        assertEquals(RepeatMode.ALL, restored.repeat)
        assertFalse(restored.isPlaying)

        // a track deleted meanwhile is dropped and the order stays consistent
        val partial = PlayerState()
        partial.restoreState(saved) { id -> tracks.firstOrNull { it.id == id && id != "t5" } }
        assertEquals(order.filter { it != "t5" }, ids(partial))
        assertNotEquals(-1, partial.index)
    }

    @Test
    fun russianTrackPlurals() {
        assertEquals("1 трек", tracksLabel(1))
        assertEquals("3 трека", tracksLabel(3))
        assertEquals("11 треков", tracksLabel(11))
        assertEquals("21 трек", tracksLabel(21))
        assertEquals("25 треков", tracksLabel(25))
    }

    @Test
    fun changedSortReordersTheQueueAroundTheCurrentTrack() {
        val player = PlayerState()
        player.playQueue(tracks, tracks[2], "p")
        player.syncWithPlaylist("p", tracks.reversed())
        assertEquals(tracks.reversed().map { it.id }, ids(player))
        assertEquals("t3", player.current?.id)
        player.next()
        assertEquals("t2", player.current?.id)
    }

    @Test
    fun changedSortKeepsTheShuffledOrder() {
        val player = PlayerState()
        player.playQueue(tracks, tracks[0], "p")
        player.toggleShuffle()
        val shuffled = ids(player)
        player.syncWithPlaylist("p", tracks.reversed())
        assertEquals(shuffled, ids(player))
        player.toggleShuffle()
        assertEquals(tracks.reversed().map { it.id }, ids(player))
    }
}
