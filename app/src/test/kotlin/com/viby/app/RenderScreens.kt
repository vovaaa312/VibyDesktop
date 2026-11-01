package com.viby.app

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.viby.app.state.AppState
import com.viby.app.state.Screen
import com.viby.app.state.SidePanel
import com.viby.app.theme.VibyTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** Renders the main screens headlessly into PNGs: ./gradlew :app:renderScreens */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screens").apply { mkdirs() }
    val state = AppState()
    val chill = state.library.playlists.first()
    state.playPlaylist(chill, chill.tracks[2])
    state.player.positionMs = 74_000

    fun shot(name: String, setup: () -> Unit) {
        setup()
        val scene = ImageComposeScene(1360, 860, Density(1f)) { VibyTheme { App(state) } }
        scene.render(0)
        val image = scene.render(1_000_000_000)
        File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("rendered $name")
    }

    shot("1-home") { state.navigate(Screen.Home) }
    shot("2-playlist") { state.navigate(Screen.PlaylistScreen(chill.id)) }
    shot("3-lyrics") {
        state.navigate(Screen.PlaylistScreen(state.library.playlists[3].id))
        state.sidePanel = SidePanel.LYRICS
    }
    shot("4-add") { state.sidePanel = SidePanel.QUEUE; state.navigate(Screen.Add) }
    shot("5-downloads") {
        state.downloads.enqueue("https://www.youtube.com/playlist?list=PL123", chill.id)
        state.downloads.enqueue("https://youtu.be/abcdefghijk", chill.id)
        state.navigate(Screen.Downloads)
    }
    shot("6-settings") { state.navigate(Screen.Settings) }
}
