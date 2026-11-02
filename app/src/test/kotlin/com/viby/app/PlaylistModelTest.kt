package com.viby.app

import com.viby.app.model.Playlist
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaylistModelTest {

    private val radioUrl = "https://www.youtube.com/watch?v=abcdefghijk&list=RDabcdefghijk"

    private fun playlist(sourceUrl: String?, youtubePlaylistId: String?) =
        Playlist("p", "p", emptyList(), sourceUrl = sourceUrl, youtubePlaylistId = youtubePlaylistId)

    @Test
    fun radioOffersSavingUntilItIsLinkedToAnOwnedPlaylist() {
        assertTrue(playlist(radioUrl, null).isUnsavedRadio)
        assertTrue(playlist(radioUrl, "RDabcdefghijk").isUnsavedRadio)
        // saved: the source now points at the private playlist created on YouTube
        assertFalse(playlist(radioUrl, "PLsaved").isUnsavedRadio)
        assertFalse(playlist("https://www.youtube.com/playlist?list=PL1", "PL1").isUnsavedRadio)
        assertFalse(playlist(null, null).isUnsavedRadio)
    }
}
