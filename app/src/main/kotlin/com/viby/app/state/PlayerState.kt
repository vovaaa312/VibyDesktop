package com.viby.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.viby.app.model.RepeatMode
import com.viby.app.model.Track
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

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
 * Playback queue modelled on Media3 like the Android app: [items] keep the list order, a separate
 * shuffle order is used while shuffle is on, and queue edits follow Android's
 * PlaybackQueueController (customized queues, insert after current, reset).
 * Loading audio is delegated to [loader] because local files and online streams differ.
 */
class PlayerState(private val audio: AudioOutput? = null) {
    /** Media items in list order (Media3 window indices). */
    var items by mutableStateOf<List<Track>>(emptyList())
        private set
    /** Index into [items] of the current track, -1 when nothing is loaded. */
    var currentIndex by mutableStateOf(-1)
        private set
    private var shuffleOrder by mutableStateOf(IntArray(0))

    /** Playlist whose tracks were loaded (Android loadedPlaylist). */
    var contextPlaylistId by mutableStateOf<String?>(null)
        private set
    /** Edited by the user: no longer mirrors its playlist (Android queueCustomized). */
    var customized by mutableStateOf(false)
        private set

    var isPlaying by mutableStateOf(false)
        private set
    /** True while a stream URL is being resolved for a track that is not downloaded yet. */
    var isLoading by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableStateOf(RepeatMode.OFF)

    private var volumeState by mutableStateOf(0.8f)
    private var mutedState by mutableStateOf(false)

    /** Starts [track] at a position, optionally paused. Set by AppState. */
    var loader: (track: Track, startMs: Long, paused: Boolean) -> Unit = { _, _, _ -> }

    /** Called after every change worth persisting (Android PlaybackStateStore triggers). */
    var onStateChanged: () -> Unit = {}

    var volume: Float
        get() = volumeState
        set(value) {
            volumeState = value.coerceIn(0f, 1f)
            mutedState = false
            applyVolume()
            onStateChanged()
        }

    var muted: Boolean
        get() = mutedState
        set(value) {
            mutedState = value
            applyVolume()
        }

    val current: Track? get() = items.getOrNull(currentIndex)

    /** Item indices in the order they will play. */
    private val playbackOrder: IntArray
        get() = if (shuffle && shuffleOrder.size == items.size) shuffleOrder else IntArray(items.size) { it }

    /** The queue as shown in the side panel: playback order. */
    val queue: List<Track> get() = playbackOrder.map { items[it] }

    /** Position of the current track in [queue]. */
    val index: Int get() = playbackOrder.indexOf(currentIndex)

    init {
        applyVolume()
    }

    // ---------------------------------------------------------------- loading

    /**
     * Android PlaybackQueueController.load / playLocalTrack: a track of the queue that is already
     * loaded for this playlist is just jumped to; otherwise the playlist replaces the queue.
     */
    fun playQueue(tracks: List<Track>, start: Track, playlistId: String?) {
        if (playlistId != null && playlistId == contextPlaylistId) {
            val existing = items.indexOfFirst { it.id == start.id }
            if (existing >= 0) {
                playItem(existing)
                return
            }
        }
        load(tracks, tracks.indexOfFirst { it.id == start.id }.coerceAtLeast(0), playlistId, customized = false)
        playItem(currentIndex)
    }

    /** A library section (favourites, an artist…): its list always replaces the queue as shown. */
    fun replaceQueue(tracks: List<Track>, start: Track, contextId: String) {
        load(tracks, tracks.indexOfFirst { it.id == start.id }.coerceAtLeast(0), contextId, customized = false)
        playItem(currentIndex)
    }

    /** Android playResolvedStream: a pending track plays among the local ones; the queue is custom. */
    fun playWithPending(tracks: List<Track>, start: Track, playlistId: String?) {
        load(tracks, tracks.indexOfFirst { it.id == start.id }.coerceAtLeast(0), playlistId, customized = true)
        playItem(currentIndex)
    }

    private fun load(tracks: List<Track>, startIndex: Int, playlistId: String?, customized: Boolean) {
        items = tracks
        currentIndex = startIndex
        contextPlaylistId = playlistId
        this.customized = customized
        shuffleOrder = if (shuffle) shuffledStartingAt(startIndex) else IntArray(0)
    }

    /** Loads the item at [itemIndex] from the start; [paused] leaves it ready without playing. */
    private fun playItem(itemIndex: Int, paused: Boolean = false) {
        if (itemIndex !in items.indices) return
        currentIndex = itemIndex
        positionMs = 0
        isPlaying = !paused
        loader(items[itemIndex], 0, paused)
        onStateChanged()
    }

    /** Sleep timer: stops the sound and keeps the place in the queue. */
    fun pause() {
        if (isPlaying) togglePlay()
    }

    /**
     * Sleep timer "до конца трека": the track ended, so the next one is loaded but not started;
     * pressing play tomorrow continues the queue.
     */
    fun finishAndStop() {
        val order = playbackOrder
        val position = order.indexOf(currentIndex)
        when {
            repeat == RepeatMode.ONE -> playItem(currentIndex, paused = true)
            position + 1 < order.size -> playItem(order[position + 1], paused = true)
            repeat != RepeatMode.OFF && order.isNotEmpty() -> playItem(order[0], paused = true)
            else -> {
                isPlaying = false
                positionMs = 0
                audio?.stop()
                onStateChanged()
            }
        }
    }

    /** Jump from the queue panel; [queuePosition] is a position in [queue]. */
    fun jumpTo(queuePosition: Int) {
        playbackOrder.getOrNull(queuePosition)?.let(::playItem)
    }

    // -------------------------------------------------------------- transport

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
        onStateChanged()
    }

    /** Media3 seekToNext / automatic transition, honouring shuffle and repeat. */
    fun next(auto: Boolean = false) {
        if (items.isEmpty()) return
        if (auto && repeat == RepeatMode.ONE) {
            playItem(currentIndex)
            return
        }
        val order = playbackOrder
        val position = order.indexOf(currentIndex)
        when {
            position + 1 < order.size -> playItem(order[position + 1])
            repeat != RepeatMode.OFF -> playItem(order[0])
            else -> {
                isPlaying = false
                positionMs = 0
                audio?.stop()
                onStateChanged()
            }
        }
    }

    /** Media3 seekToPrevious: restart after 3 s, otherwise the previous item (wraps with repeat). */
    fun previous() {
        val order = playbackOrder
        val position = order.indexOf(currentIndex)
        when {
            positionMs > MAX_SEEK_TO_PREVIOUS_MS || position < 0 -> seekTo(0)
            position > 0 -> playItem(order[position - 1])
            repeat != RepeatMode.OFF && order.isNotEmpty() -> playItem(order.last())
            else -> seekTo(0)
        }
    }

    fun seek(fraction: Float) {
        val track = current ?: return
        seekTo((track.durationMs * fraction.coerceIn(0f, 1f)).toLong())
    }

    private fun seekTo(ms: Long) {
        positionMs = ms
        audio?.seek(ms)
        onStateChanged()
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
        onStateChanged()
    }

    /** Android PlaybackService: enabling shuffle moves the current item to the start of the order. */
    fun toggleShuffle() {
        setShuffleEnabled(!shuffle)
    }

    fun setShuffleEnabled(enabled: Boolean) {
        shuffle = enabled
        shuffleOrder = if (enabled) shuffledStartingAt(currentIndex.coerceAtLeast(0)) else IntArray(0)
        onStateChanged()
    }

    // ------------------------------------------------------------ queue edits

    /** Android addToQueue: appended to the queue. */
    fun enqueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (items.isEmpty()) {
            playQueue(tracks, tracks.first(), null)
            customized = true
            return
        }
        val start = items.size
        items = items + tracks
        if (shuffle) shuffleOrder = shuffleOrder + IntArray(tracks.size) { start + it }
        customized = true
        onStateChanged()
    }

    /** Android insertAfterCurrent; with shuffle the new items follow the current one in shuffle order. */
    fun enqueueNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (items.isEmpty()) {
            playQueue(tracks, tracks.first(), null)
            customized = true
            return
        }
        if (shuffle) {
            // Media3 receives the items at the end, then the order is rewritten (PlaybackQueueOrder)
            val start = items.size
            items = items + tracks
            shuffleOrder = moveInsertedRangeAfterCurrent(
                shuffleOrder + IntArray(tracks.size) { start + it }, currentIndex, start, tracks.size)
        } else {
            val at = currentIndex + 1
            items = items.take(at) + tracks + items.drop(at)
        }
        customized = true
        onStateChanged()
    }

    /** Removes the item at [queuePosition] (a position in [queue]); the current track stays. */
    fun removeFromQueue(queuePosition: Int) {
        val itemIndex = playbackOrder.getOrNull(queuePosition) ?: return
        if (itemIndex == currentIndex) return
        items = items.filterIndexed { i, _ -> i != itemIndex }
        if (itemIndex < currentIndex) currentIndex--
        if (shuffleOrder.isNotEmpty()) {
            shuffleOrder = shuffleOrder.filter { it != itemIndex }.map { if (it > itemIndex) it - 1 else it }.toIntArray()
        }
        customized = true
        onStateChanged()
    }

    /** Drag in the queue panel: reorders the shuffle order while shuffled, the list otherwise. */
    fun moveInQueue(from: Int, to: Int) {
        val order = playbackOrder.toMutableList()
        if (from !in order.indices || to !in order.indices || from == to) return
        if (shuffle) {
            order.add(to, order.removeAt(from))
            shuffleOrder = order.toIntArray()
        } else {
            val currentTrack = current
            val list = items.toMutableList()
            list.add(to, list.removeAt(from))
            items = list
            currentIndex = list.indexOf(currentTrack)
        }
        customized = true
        onStateChanged()
    }

    /**
     * Android resetToActivePlaylist: the queue mirrors [tracks] again, the current track keeps its
     * position when it is still there, and shuffle is turned off.
     */
    fun resetTo(tracks: List<Track>, playlistId: String?) {
        val currentId = current?.id
        val keep = tracks.indexOfFirst { it.id == currentId }
        val wasPlaying = isPlaying
        shuffle = false
        if (tracks.isEmpty()) {
            items = emptyList()
            currentIndex = -1
            isPlaying = false
            audio?.stop()
        } else if (keep >= 0) {
            load(tracks, keep, playlistId, customized = false)
        } else {
            load(tracks, 0, playlistId, customized = false)
            positionMs = 0
            loader(tracks[0], 0, !wasPlaying)
        }
        contextPlaylistId = playlistId
        customized = false
        onStateChanged()
    }

    /**
     * Android syncWithActivePlaylist: an unedited queue follows its playlist; newly downloaded
     * tracks are appended, a different order rebuilds the queue around the current track.
     */
    fun syncWithPlaylist(playlistId: String, playable: List<Track>) {
        if (playlistId != contextPlaylistId || customized || items.isEmpty()) return
        val prefixMatches = items.size <= playable.size &&
            items.indices.all { items[it].id == playable[it].id }
        if (prefixMatches && playable.size > items.size) {
            val start = items.size
            val added = playable.size - start
            items = playable
            if (shuffle) shuffleOrder = shuffleOrder + IntArray(added) { start + it }
            onStateChanged()
        } else if (!prefixMatches) {
            val currentId = current?.id
            val newIndex = playable.indexOfFirst { it.id == currentId }
            if (newIndex < 0) return // the playing track left the playlist: keep playing it
            // a shuffled queue keeps its order: only the indices move with the new list
            val shuffledIds = if (shuffle && shuffleOrder.size == items.size) shuffleOrder.map { items[it].id } else null
            items = playable
            currentIndex = newIndex
            shuffleOrder = when {
                !shuffle -> IntArray(0)
                shuffledIds == null -> shuffledStartingAt(newIndex)
                else -> remapOrder(shuffledIds, playable)
            }
            onStateChanged()
        } else {
            // same tracks, refreshed metadata (titles, covers)
            items = playable
        }
    }

    /** The loaded playlist was renamed: the queue keeps following it under the new name. */
    fun renameContext(from: String, to: String) {
        if (contextPlaylistId != from) return
        contextPlaylistId = to
        onStateChanged()
    }

    /** Android clearIfLoaded: the loaded playlist was deleted. */
    fun clearIfLoaded(playlistId: String) {
        if (playlistId != contextPlaylistId) return
        audio?.stop()
        items = emptyList()
        currentIndex = -1
        isPlaying = false
        contextPlaylistId = null
        customized = false
        onStateChanged()
    }

    // ------------------------------------------------------------ persistence

    /** Android PlaybackStateStore: queue, current item, position, shuffle order and repeat. */
    fun saveState(): JSONObject = JSONObject()
        .put("queue", JSONArray(items.map { it.id }))
        .put("currentId", current?.id)
        .put("positionMs", positionMs)
        .put("shuffle", shuffle)
        .put("shuffleOrder", JSONArray(shuffleOrder.toList()))
        .put("repeat", repeat.name)
        .put("playlist", contextPlaylistId)
        .put("customized", customized)
        .put("volume", volumeState.toDouble())

    /**
     * Restores a saved queue paused at its position. Tracks that no longer exist are dropped and
     * the shuffle order is remapped, like PlaybackStateStore.restore.
     */
    fun restoreState(json: JSONObject, resolve: (String) -> Track?) {
        volumeState = json.optDouble("volume", 0.8).toFloat().coerceIn(0f, 1f)
        applyVolume()
        repeat = runCatching { RepeatMode.valueOf(json.optString("repeat", "OFF")) }.getOrDefault(RepeatMode.OFF)
        val saved = json.optJSONArray("queue") ?: return
        val oldToNew = IntArray(saved.length()) { -1 }
        val restored = mutableListOf<Track>()
        for (i in 0 until saved.length()) {
            resolve(saved.optString(i))?.let {
                oldToNew[i] = restored.size
                restored += it
            }
        }
        if (restored.isEmpty()) return
        items = restored
        val currentId = json.optString("currentId", "")
        currentIndex = restored.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
        positionMs = if (restored.getOrNull(currentIndex)?.id == currentId) json.optLong("positionMs", 0) else 0
        contextPlaylistId = json.optString("playlist", "").ifEmpty { null }
        customized = json.optBoolean("customized", false)
        shuffle = json.optBoolean("shuffle", false)
        val savedOrder = json.optJSONArray("shuffleOrder")
        val order = (0 until (savedOrder?.length() ?: 0)).mapNotNull { oldToNew.getOrNull(savedOrder!!.optInt(it)) }
            .filter { it >= 0 }
        shuffleOrder = when {
            !shuffle -> IntArray(0)
            order.size == restored.size -> order.toIntArray()
            else -> shuffledStartingAt(currentIndex)
        }
        isPlaying = false
        loader(restored[currentIndex], positionMs, true)
    }

    /** Mirrors the engine position into Compose state for the seek bar. */
    suspend fun runClock() {
        val output = audio ?: return
        var sinceSave = 0L
        while (true) {
            delay(TICK_MS)
            if (isPlaying && !isLoading && output.hasSource) {
                positionMs = output.positionMs.coerceAtMost(current?.durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE)
                sinceSave += TICK_MS
                if (sinceSave >= SAVE_POSITION_MS) {
                    sinceSave = 0
                    onStateChanged()
                }
            }
        }
    }

    private fun applyVolume() {
        audio?.setVolume(if (mutedState) 0f else volumeState)
    }

    /** [orderIds] as indices into [tracks]; tracks that were not in the order go to the end. */
    private fun remapOrder(orderIds: List<String>, tracks: List<Track>): IntArray {
        val index = tracks.withIndex().associate { (i, t) -> t.id to i }
        val kept = orderIds.mapNotNull(index::get).distinct()
        return (kept + tracks.indices.filter { it !in kept.toSet() }).toIntArray()
    }

    private fun shuffledStartingAt(start: Int): IntArray {
        if (items.isEmpty()) return IntArray(0)
        val rest = items.indices.filter { it != start }.shuffled()
        return (listOf(start.coerceIn(items.indices)) + rest).distinct().toIntArray()
    }

    companion object {
        private const val TICK_MS = 200L
        private const val SAVE_POSITION_MS = 5_000L
        /** Media3 default maxSeekToPreviousPositionMs. */
        const val MAX_SEEK_TO_PREVIOUS_MS = 3_000L

        /** Port of Android PlaybackQueueOrder.moveInsertedRangeAfterCurrent (core keeps it package-private). */
        fun moveInsertedRangeAfterCurrent(order: IntArray, currentIndex: Int, rangeStart: Int, rangeCount: Int): IntArray =
            com.viby.core.playback.PlaybackQueueOrderAccess.moveInsertedRangeAfterCurrent(
                order, currentIndex, rangeStart, rangeCount)
    }
}
