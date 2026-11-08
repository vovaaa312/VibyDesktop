package com.viby.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.viby.app.state.AppState
import com.viby.app.state.Screen
import com.viby.app.state.SidePanel
import com.viby.app.theme.AccentTheme
import com.viby.app.theme.VibyTheme
import com.viby.app.ui.CoverCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** Renders the layout at several window sizes (dev-home library): ./gradlew :app:renderLayouts */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>): Unit = runBlocking {
    val out = File(args.firstOrNull() ?: "build/screens").apply { mkdirs() }
    val state = withContext(Dispatchers.Main) { AppState() }
    CoverCache.directory = state.dirs.root().resolve("covers")
    while (!state.library.loaded) delay(100)
    val playlist = state.library.playlists.maxByOrNull { it.tracks.size }
    withContext(Dispatchers.Main) { playlist?.tracks?.firstOrNull()?.let { state.playPlaylist(playlist, it) } }

    suspend fun shot(
        name: String, width: Int, height: Int,
        interact: suspend (ImageComposeScene) -> Unit = {}, setup: () -> Unit,
    ) = withContext(Dispatchers.Main) {
        setup()
        val scene = ImageComposeScene(width, height, Density(1f)) { VibyTheme { App(state) } }
        scene.render(0)
        delay(800)
        interact(scene)
        scene.render(500_000_000)
        delay(300)
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
    // the long name from the GIF: the title must shrink smoothly instead of wrapping
    val longName = "Радио_ Кукла колдуна"
    withContext(Dispatchers.Main) { state.engine.library().createPlaylist(longName) }
    while (state.library.playlist(longName) == null) delay(100)
    for (width in listOf(1100, 1300, 1500, 1700)) {
        shot("title-$width", width, 700) { state.navigate(Screen.PlaylistScreen(longName)); state.sidePanel = SidePanel.QUEUE }
    }
    shot("playlist-scrolled", 1360, 860, interact = { scene ->
        repeat(6) {
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(700f, 500f), scrollDelta = Offset(0f, 3f))
            scene.render(it * 50_000_000L)
            delay(50)
        }
    }) { playlist?.let { state.navigate(Screen.PlaylistScreen(it.id)) }; state.sidePanel = SidePanel.NONE }
    shot("home-narrow-shelf", 980, 760, interact = { scene ->
        repeat(8) {
            scene.sendPointerEvent(PointerEventType.Scroll, Offset(600f, 400f), scrollDelta = Offset(0f, 3f))
            scene.render(it * 50_000_000L)
            delay(50)
        }
        scene.sendPointerEvent(PointerEventType.Move, Offset(600f, 560f))
        scene.sendPointerEvent(PointerEventType.Move, Offset(610f, 565f))
    }) { state.navigate(Screen.Home); state.sidePanel = SidePanel.NONE }
    for (theme in AccentTheme.entries) {
        val name = theme.name.lowercase()
        shot("theme-$name-settings", 1200, 700) {
            state.setTheme(theme); state.navigate(Screen.Settings); state.sidePanel = SidePanel.NONE
        }
        shot("theme-$name-playlist", 1200, 700) {
            playlist?.let { state.navigate(Screen.PlaylistScreen(it.id)) }
            state.sidePanel = SidePanel.QUEUE
        }
    }
    withContext(Dispatchers.Main) { state.setTheme(AccentTheme.VIBY) }
    // the ffmpeg 403 output that used to cover half of the window
    val forbidden = "[in#0 @ 00000246939cfb40] Error opening input: Server returned 403 Forbidden (access denied)\n" +
        "Error opening input file https://rr2---sn-jxnoxu-2gbl.googlevideo.com/videoplayback?" + "expire=1793806357&ei=x".repeat(40) +
        "\nError opening input files: Server returned 403 Forbidden (access denied)"
    shot("toast-error", 1360, 860) {
        playlist?.let { state.navigate(Screen.PlaylistScreen(it.id)) }
        state.showError(forbidden, "Не удалось воспроизвести трек")
    }
    shot("toast-long-info", 1000, 600) { state.showMessage("Длинное сообщение. ".repeat(30)) }
    shot("error-details", 1360, 860) { state.consumeMessage(); state.errorDetails = forbidden }
    withContext(Dispatchers.Main) { state.errorDetails = null }
    shot("storage", 1360, 900, interact = { scene ->
        while (state.storage.report == null || state.storage.busy != null) delay(100)
        scene.render(100_000_000)
    }) { state.navigate(Screen.Storage); state.sidePanel = SidePanel.NONE }
    shot("settings-library", 1360, 900) { state.navigate(Screen.Settings) }
    // a saved filter must be visible in the header, not silently hide tracks
    playlist?.let { p ->
        val saved = state.library.view(p.id)
        shot("playlist-filter", 1360, 860) {
            state.library.saveView(p.id, com.viby.app.model.PlaylistView(com.viby.app.model.SortMode.TITLE, com.viby.app.model.TrackFilter.NOT_DOWNLOADED))
            state.navigate(Screen.PlaylistScreen(p.id))
        }
        withContext(Dispatchers.Main) { state.library.saveView(p.id, saved) }
    }
    withContext(Dispatchers.Main) { state.engine.library().deletePlaylist(longName); state.close() }
    kotlin.system.exitProcess(0)
}
