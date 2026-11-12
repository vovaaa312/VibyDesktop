package com.viby.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Таймер сна (guideline stage 5): pauses playback after a number of minutes, or when the current
 * track ends. Not saved: a restart clears it, like closing the app would.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val onElapsed: () -> Unit,
) {
    /** Time left while a minutes timer runs, null otherwise. */
    var remainingMs by mutableStateOf<Long?>(null)
        private set

    /** Stop after the track that is playing now. */
    var untilTrackEnds by mutableStateOf(false)
        private set

    val active: Boolean get() = remainingMs != null || untilTrackEnds

    private var job: Job? = null

    fun start(minutes: Int) {
        cancel()
        val endsAt = now() + minutes * 60_000L
        remainingMs = minutes * 60_000L
        job = scope.launch {
            while (true) {
                delay(TICK_MS)
                val left = endsAt - now()
                if (left <= 0) break
                remainingMs = left
            }
            remainingMs = null
            job = null
            onElapsed()
        }
    }

    fun stopAfterTrack() {
        cancel()
        untilTrackEnds = true
    }

    fun cancel() {
        job?.cancel()
        job = null
        remainingMs = null
        untilTrackEnds = false
    }

    /** The current track ended: true when playback must stop here instead of going on. */
    fun consumeTrackEnd(): Boolean {
        if (!untilTrackEnds) return false
        untilTrackEnds = false
        return true
    }

    companion object {
        private const val TICK_MS = 1_000L
        val PRESETS_MINUTES = listOf(15, 30, 45, 60, 90)
    }
}
