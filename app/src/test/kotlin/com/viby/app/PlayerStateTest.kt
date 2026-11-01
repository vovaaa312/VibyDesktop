package com.viby.app

import com.viby.app.model.RepeatMode
import com.viby.app.model.Track
import com.viby.app.state.PlayerState
import com.viby.app.ui.tracksLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerStateTest {

    private val tracks = (1..5).map { Track("t$it", "Track $it", "Artist", 180_000) }

    @Test
    fun nextStopsAtEndWithoutRepeatAndWrapsWithRepeatAll() {
        val player = PlayerState()
        player.playQueue(tracks, tracks.last(), null)
        player.next()
        assertFalse(player.isPlaying)
        assertEquals("t5", player.current?.id)

        player.repeat = RepeatMode.ALL
        player.jumpTo(4)
        player.next()
        assertEquals("t1", player.current?.id)
    }

    @Test
    fun repeatOneRestartsOnlyOnAutomaticAdvance() {
        val player = PlayerState()
        player.playQueue(tracks, tracks[1], null)
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
        player.playQueue(tracks, tracks[2], null)
        player.positionMs = 10_000
        player.previous()
        assertEquals("t3", player.current?.id)
        assertEquals(0, player.positionMs)
        player.previous()
        assertEquals("t2", player.current?.id)
    }

    @Test
    fun shuffleKeepsCurrentTrackAndHistory() {
        val player = PlayerState()
        player.playQueue(tracks, tracks[1], null)
        player.toggleShuffle()
        assertTrue(player.shuffle)
        assertEquals("t2", player.current?.id)
        assertEquals(listOf("t1", "t2"), player.queue.take(2).map { it.id })
        assertEquals(tracks.map { it.id }.toSet(), player.queue.map { it.id }.toSet())
    }

    @Test
    fun removingEarlierQueueItemKeepsCurrentTrack() {
        val player = PlayerState()
        player.playQueue(tracks, tracks[3], null)
        player.removeFromQueue(0)
        assertEquals("t4", player.current?.id)
        player.removeFromQueue(player.index)
        assertEquals(4, player.queue.size)
    }

    @Test
    fun russianTrackPlurals() {
        assertEquals("1 трек", tracksLabel(1))
        assertEquals("3 трека", tracksLabel(3))
        assertEquals("11 треков", tracksLabel(11))
        assertEquals("21 трек", tracksLabel(21))
        assertEquals("25 треков", tracksLabel(25))
    }
}
