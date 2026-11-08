package com.viby.app

import com.viby.app.model.Playlist
import com.viby.app.model.Track
import com.viby.app.state.LibrarySearch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibrarySearchTest {

    private val blood = Track("1", "Группа крови", "Кино", 1, videoId = "a")
    private val star = Track("2", "Звезда по имени Солнце", "Кино", 1, videoId = "b")
    private val hedgehog = Track("3", "Ёжик в тумане", "Аквариум", 1, videoId = "c")
    private val bloodCopy = blood.copy(id = "4")
    private val pending = Track("5", "Кукушка", "Кино", 1, videoId = "d", downloadProgress = 0)
    private val pendingCopy = pending.copy(id = "6", downloadProgress = null)

    private val rock = Playlist("Рок", "Рок", listOf(blood, star, hedgehog, pending))
    private val favs = Playlist("favs", "favs", listOf(bloodCopy, pendingCopy))
    private val library = listOf(rock, favs)

    private fun titles(query: String) = LibrarySearch.search(library, query).tracks.map { it.track.title }

    @Test
    fun everyWordMustMatchTitleOrArtist() {
        assertEquals(listOf("Группа крови"), titles("кино группа"))
        assertEquals(emptyList<String>(), titles("кино аквариум"))
    }

    @Test
    fun ignoresCaseAndYo() {
        assertEquals(listOf("Ёжик в тумане"), titles("ЕЖИК"))
    }

    @Test
    fun sameSongInSeveralPlaylistsIsListedOnce() {
        val hit = LibrarySearch.search(library, "группа").tracks.single()
        assertEquals(listOf("Рок", "favs"), hit.playlists.map { it.name })
    }

    @Test
    fun prefersTheDownloadedCopy() {
        val hit = LibrarySearch.search(library, "кукушка").tracks.single()
        assertEquals("6", hit.track.id)
        assertEquals("favs", hit.playlist.name)
    }

    @Test
    fun titleStartComesFirst() {
        // "Кукушка" starts with the query, "Кино" tracks match at the artist, "Аквариум" mid-word
        val order = titles("к")
        assertEquals("Кукушка", order.first())
        assertEquals("Ёжик в тумане", order.last())
    }

    @Test
    fun findsPlaylistsByName() {
        val result = LibrarySearch.search(library, "фав")
        assertTrue(result.playlists.isEmpty())
        assertEquals(listOf("favs"), LibrarySearch.search(library, "FAV").playlists.map { it.name })
    }

    @Test
    fun blankQueryFindsNothing() {
        assertTrue(LibrarySearch.search(library, "   ").isEmpty)
    }
}
