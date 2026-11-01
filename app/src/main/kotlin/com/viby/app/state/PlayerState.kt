package com.viby.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.viby.app.model.RepeatMode
import com.viby.app.model.Track
import kotlinx.coroutines.delay

/** Sound output used by [PlayerState]; the app wires it to core's AudioEngine. */
interface AudioOutput {
    fun pause()
    fun resume()
    fun seek(positionMs: Long)
    fun stop()
    fun setVolume(volume: Float)
    val positionMs: Long
    val hasSource: Boolean
}

/**
 * Queue, transport and volume. Loading a track is delegated to [loader] because local files and
 * pending downloads (online streams) are resolved differently.
 */
class PlayerState(private val audio: AudioOutput? = null) {
    val queue = mutableStateListOf<Track>()
    var index by mutableStateOf(-1)
        private set
    var contextPlaylistId by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    /** True while a stream URL is being resolved for a track that is not downloaded yet. */
    var isLoading by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var shuffle by mutableStateOf(false)
    var repeat by mutableStateOf(RepeatMode.OFF)

    private var volumeState by mutableStateOf(0.8f)
    private var mutedState by mutableStateOf(false)

    /** Starts [track] at a position, optionally paused. Set by AppState. */
    var loader: (track: Track, startMs: Long, paused: Boolean) -> Unit = { _, _, _ -> }

    var volume: Float
        get() = volumeState
        set(value) {
            volumeState = value.coerceIn(0f, 1f)
            mutedState = false
            applyVolume()
        }

    var muted: Boolean
        get() = mutedState
        set(value) {
            mutedState = value
            applyVolume()
        }

    val current: Track? get() = queue.getOrNull(index)

    init {
        applyVolume()
    }

    fun playQueue(tracks: List<Track>, start: Track, playlistId: String?) {
        val ordered = if (shuffle) listOf(start) + (tracks - start).shuffled() else tracks
        queue.clear()
        queue.addAll(ordered)
        contextPlaylistId = playlistId
        jumpTo(queue.indexOf(start))
    }

    fun jumpTo(newIndex: Int) {
        if (newIndex !in queue.indices) return
        index = newIndex
        positionMs = 0
        isPlaying = true
        loader(queue[newIndex], 0, false)
    }

    fun togglePlay() {
        val track = current ?: return
        if (isPlaying) {
            audio?.pause()
            isPlaying = false
        } else {
            isPlaying = true
            // nothing loaded yet, e.g. after a restart or a stopped queue
            if (audio?.hasSource == false) loader(track, positionMs, false) else audio?.resume()
        }
    }

    fun next(auto: Boolean = false) {
        if (queue.isEmpty()) return
        when {
            auto && repeat == RepeatMode.ONE -> jumpTo(index)
            index + 1 < queue.size -> jumpTo(index + 1)
            repeat == RepeatMode.ALL -> jumpTo(0)
            else -> {
                isPlaying = false
                positionMs = 0
                audio?.stop()
            }
        }
    }

    fun previous() {
        if (positionMs > 3_000 || index == 0) seekTo(0) else jumpTo(index - 1)
    }

    fun seek(fraction: Float) {
        val track = current ?: return
        seekTo((track.durationMs * fraction.coerceIn(0f, 1f)).toLong())
    }

    private fun seekTo(ms: Long) {
        positionMs = ms
        audio?.seek(ms)
    }

    /** The audio engine reached the end of the current source. */
    fun onEnded() = next(auto = true)

    /** The current track could not be played; stop instead of skipping through a broken queue. */
    fun onFailed() {
        isPlaying = false
        isLoading = false
        audio?.stop()
    }

    fun cycleRepeat() {
        repeat = RepeatMode.entries[(repeat.ordinal + 1) % RepeatMode.entries.size]
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        val track = current ?: return
        val upcoming = queue.drop(index + 1).let { if (shuffle) it.shuffled() else it }
        val head = queue.take(index + 1)
        queue.clear()
        queue.addAll(head + upcoming)
        index = queue.indexOf(track)
    }

    fun enqueueNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (queue.isEmpty()) { playQueue(tracks, tracks.first(), null); return }
        queue.addAll(index + 1, tracks)
    }

    fun enqueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (queue.isEmpty()) { playQueue(tracks, tracks.first(), null); return }
        queue.addAll(tracks)
    }

    fun removeFromQueue(position: Int) {
        if (position == index || position !in queue.indices) return
        queue.removeAt(position)
        if (position < index) index--
    }

    /** Mirrors the engine position into Compose state for the seek bar. */
    suspend fun runClock() {
        val output = audio ?: return
        while (true) {
            delay(TICK_MS)
            if (isPlaying && !isLoading && output.hasSource) {
                positionMs = output.positionMs.coerceAtMost(current?.durationMs ?: Long.MAX_VALUE)
            }
        }
    }

    private fun applyVolume() {
        audio?.setVolume(if (mutedState) 0f else volumeState)
    }

    private companion object {
        const val TICK_MS = 200L
    }
}
