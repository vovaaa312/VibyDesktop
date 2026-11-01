package com.viby.app.state

import com.viby.app.model.Playlist
import com.viby.app.model.Track
import kotlin.random.Random

/** Placeholder library so the UI can be explored before the SQLite library is wired in. */
object DemoData {

    private val artists = listOf(
        "Neon Harbor", "Velvet Static", "Paper Moons", "Low Orbit", "Mira Sol",
        "The Quiet Engines", "Saltwater Radio", "Koda Lane", "Glass Animals Club", "Echo Parade",
    )

    private val words = listOf(
        "Midnight", "Signal", "Golden", "Afterglow", "Paper", "Static", "Ocean", "Drive",
        "Lights", "Fading", "Northern", "Echoes", "Summer", "Satellite", "Velvet", "Home",
        "Gravity", "Runaway", "Silver", "Dreams", "City", "Rain", "Fire", "Neon",
    )

    fun playlists(): List<Playlist> {
        val random = Random(42)
        val now = System.currentTimeMillis()
        fun track(index: Int, playlist: String, pending: Boolean = false): Track {
            val title = (1..random.nextInt(1, 4)).joinToString(" ") { words.random(random) }
            return Track(
                id = "$playlist-$index",
                title = title,
                artist = artists.random(random),
                durationMs = random.nextLong(125_000, 330_000),
                addedAt = now - random.nextLong(0, 90L * 24 * 3600 * 1000),
                downloadProgress = if (pending) random.nextInt(0, 90) else null,
            )
        }
        fun playlist(id: String, name: String, size: Int, pending: Int = 0, synced: Boolean = false) =
            Playlist(
                id = id,
                name = name,
                tracks = (0 until size).map { track(it, id, pending = it >= size - pending) },
                sourceUrl = "https://www.youtube.com/playlist?list=PL$id",
                youtubeSynced = synced,
            )
        return listOf(
            playlist("chill", "Chill вечер", 24, synced = true),
            playlist("drive", "В дорогу", 38),
            playlist("focus", "Фокус / работа", 52),
            playlist("gym", "Зал", 19, pending = 4),
            playlist("indie", "Indie находки", 31, synced = true),
            playlist("rock", "Старый рок", 45),
            playlist("night", "Ночной город", 17),
        )
    }

    val lyrics = """
        Это демо-текст — настоящие тексты будут
        подгружаться из YouTube Music,
        а если их там нет — из субтитров видео.

        Neon lights over the harbor
        Signal fading into the rain
        Every echo leads me farther
        Every road leads home again

        Midnight drive through silver cities
        Satellites are falling slow
        Hold the static, hold the feeling
        Never let the afterglow go
    """.trimIndent()
}
