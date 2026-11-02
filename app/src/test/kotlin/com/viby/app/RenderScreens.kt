package com.viby.app

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.viby.app.state.AppState
import com.viby.app.state.Screen
import com.viby.app.theme.VibyTheme
import com.viby.app.ui.CoverCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Headless end-to-end check: ./gradlew :app:renderScreens [-Purl=<youtube url>]
 * Starts the real engine (tools from core/build/dev-home), downloads the URL through the queue
 * and renders the main screens into app/build/screens.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val out = File(args.firstOrNull() ?: "build/screens").apply { mkdirs() }
    val url = args.getOrNull(1) ?: "https://www.youtube.com/watch?v=jNQXAC9IVRw"
    val state = withContext(Dispatchers.Main) { AppState() }
    CoverCache.directory = state.dirs.root().resolve("covers")
    // keep test downloads out of the real ~/Music/Viby
    state.settings.setMusicDirectory(state.dirs.root().resolve("Music"))
    state.start()

    withTimeout(600_000) {
        while (!state.engine.isReady) delay(200)
        withContext(Dispatchers.Main) { state.downloads.enqueue(url, "E2E") }
        while (state.downloads.jobs.isEmpty() || state.downloads.jobs.any { it.isActive }) {
            state.downloads.jobs.firstOrNull()?.let { println("${it.status} ${it.progress}% ${it.title}") }
            delay(1_000)
        }
        delay(1_000)
    }
    val job = state.downloads.jobs.first()
    println("job: ${job.status} failed=${job.failedCount} error=${job.error}")
    val track = state.library.playlist("E2E")?.tracks?.firstOrNull()
    println("track: ${track?.title} • ${track?.artist} • ${track?.filePath}")

    suspend fun shot(name: String, setup: () -> Unit) = withContext(Dispatchers.Main) {
        setup()
        val scene = ImageComposeScene(1360, 860, Density(1f)) { VibyTheme { App(state) } }
        scene.render(0)
        delay(1_500) // let covers load
        val image = scene.render(2_000_000_000)
        File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("rendered $name")
    }

    shot("1-home") { state.navigate(Screen.Home) }
    shot("2-playlist") { state.navigate(Screen.PlaylistScreen("E2E")) }
    shot("3-downloads") { state.navigate(Screen.Downloads) }
    shot("4-settings") { state.navigate(Screen.Settings) }
    withContext(Dispatchers.Main) { state.close() }
    kotlin.system.exitProcess(if (track?.filePath != null) 0 else 1)
}
