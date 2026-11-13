package com.viby.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.viby.app.model.Track
import com.viby.core.playback.AudioEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Stage 5 sound: volume normalization, gapless playback and crossfade. Tells the audio engine
 * which track comes next and how loud each one is; loudness is measured once per file in the
 * background (core LoudnessIndex).
 */
class SoundState(private val app: AppState) {

    var normalize by mutableStateOf(app.settings.normalizeVolume())
        private set

    var crossfadeSeconds by mutableStateOf(app.settings.crossfadeSeconds())
        private set

    /** "Выравниваю громкость… 40 из 520" while the library is measured, null otherwise. */
    var progress by mutableStateOf<String?>(null)
        private set

    private val measuring = mutableSetOf<String>()
    private var refreshing = false
    private var refreshAgain = false

    init {
        app.engine.audio().setCrossfadeMs(crossfadeSeconds * 1000)
    }

    fun changeNormalize(enabled: Boolean) {
        normalize = enabled
        app.settings.setNormalizeVolume(enabled)
        // the change is heard at once, faded over a fraction of a second
        app.player.current?.let(::playableFile)?.let { app.engine.audio().setGain(it, gainFor(it)) }
        updateNext()
        if (enabled) refreshLibrary()
    }

    fun changeCrossfade(seconds: Int) {
        crossfadeSeconds = seconds
        app.settings.setCrossfadeSeconds(seconds)
        app.engine.audio().setCrossfadeMs(seconds * 1000)
    }

    /** Gain for a file right now: the measured one, or 0 dB until it is measured. */
    fun gainFor(path: String): Float {
        if (!normalize) return 0f
        val known = app.engine.loudness().gainDb(path)
        if (known == null) measureSoon(path)
        return known ?: 0f
    }

    /** A track that is about to play is measured first, ahead of the library. */
    private fun measureSoon(path: String) {
        if (!app.engine.isReady || !measuring.add(path)) return
        app.scope.launch {
            try {
                withContext(Dispatchers.IO) { app.engine.loudness().measure(path) }
                if (normalize) app.engine.audio().setGain(path, gainFor(path))
            } finally {
                measuring.remove(path)
            }
        }
    }

    /**
     * The track after the current one, so the engine continues without a gap (or crossfades).
     * Called after every queue change; only downloaded files qualify, a stream starts the usual way.
     */
    fun updateNext() {
        val audio = app.engine.audio()
        if (app.sleepTimer.untilTrackEnds) {
            audio.setNext(null)
            return
        }
        val next = app.player.peekNext()
        val file = next?.let(::playableFile)
        audio.setNext(file?.let { AudioEngine.Next(it, next.durationMs, gainFor(it)) })
    }

    /** The engine moved on by itself: the queue follows without reloading the sound. */
    fun onAdvanced(source: String) {
        if (app.player.advanceTo { playableFile(it) == source }) {
            app.player.current?.let(app.history::record)
        }
        updateNext()
    }

    /** Measures the whole library in the background, at low priority. */
    fun refreshLibrary() {
        if (!normalize || !app.engine.isReady) return
        if (refreshing) {
            refreshAgain = true
            return
        }
        refreshing = true
        val files = app.library.playlists.flatMap { it.tracks }.mapNotNull(::playableFile).distinct()
        app.scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.engine.loudness().refresh(files) { done, total ->
                        if (total > 5 && (done % 5 == 0 || done == total)) {
                            app.scope.launch { progress = "Измеряю громкость треков… $done из $total" }
                        }
                    }
                }
            } catch (e: Exception) {
                // without measurements tracks simply play at their own level
            } finally {
                progress = null
                refreshing = false
                if (refreshAgain) {
                    refreshAgain = false
                    refreshLibrary()
                }
            }
        }
    }

    private fun playableFile(track: Track): String? =
        track.filePath?.takeIf { track.isDownloaded && !track.fileMissing && File(it).isFile }
}
