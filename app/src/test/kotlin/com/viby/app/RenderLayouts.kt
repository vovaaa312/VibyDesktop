package com.viby.app

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.viby.app.state.AppState
import com.viby.app.state.Screen
import com.viby.app.state.SidePanel
import com.viby.app.theme.VibyTheme
import com.viby.app.ui.CoverCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** Renders the layout at several window sizes (dev-home library): ./gradlew :app:renderLayouts */
fun main(args: Array<String>): Unit = runBlocking {
    val out = File(args.firstOrNull() ?: "build/screens").apply { mkdirs() }
    val state = withContext(Dispatchers.Main) { AppState() }
    CoverCache.directory = state.dirs.root().resolve("covers")
    while (!state.library.loaded) delay(100)
    val playlist = state.library.playlists.maxByOrNull { it.tracks.size }
    withContext(Dispatchers.Main) { playlist?.tracks?.firstOrNull()?.let { state.playPlaylist(playlist, it) } }

    suspend fun shot(name: String, width: Int, height: Int, setup: () -> Unit) = withContext(Dispatchers.Main) {
        setup()
        val scene = ImageComposeScene(width, height, Density(1f)) { VibyTheme { App(state) } }
        scene.render(0)
        delay(800)
        File(out, "$name.png").writeBytes(scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("rendered $name")
    }

    shot("narrow-playlist-lyrics", 1000, 600) {
        playlist?.let { state.navigate(Screen.PlaylistScreen(it.id)) }
        state.sidePanel = SidePanel.LYRICS
    }
    shot("narrow-home-queue", 1000, 600) { state.navigate(Screen.Home); state.sidePanel = SidePanel.QUEUE }
    shot("wide-playlist", 1360, 860) { playlist?.let { state.navigate(Screen.PlaylistScreen(it.id)) } }
    withContext(Dispatchers.Main) { state.close() }
    kotlin.system.exitProcess(0)
}
