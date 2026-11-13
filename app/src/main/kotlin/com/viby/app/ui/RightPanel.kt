package com.viby.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viby.app.model.Track
import com.viby.app.state.AppState
import com.viby.app.state.SidePanel
import com.viby.app.state.ErrorText
import com.viby.app.theme.VibyColors
import kotlin.math.roundToInt
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import com.viby.core.auth.CookieSource
import com.viby.core.lyrics.TimedLine
import com.viby.core.lyrics.YoutubeLyricsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RightPanel(state: AppState, modifier: Modifier = Modifier, width: Dp = 340.dp) {
    Panel(modifier.width(width).fillMaxHeight()) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab("Далее", state.sidePanel == SidePanel.QUEUE) { state.sidePanel = SidePanel.QUEUE }
                Spacer(Modifier.width(20.dp))
                Tab("Текст", state.sidePanel == SidePanel.LYRICS) { state.sidePanel = SidePanel.LYRICS }
                Spacer(Modifier.weight(1f))
                IconAction(Icons.Rounded.Close, "Закрыть", { state.sidePanel = SidePanel.NONE })
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(VibyColors.Divider))
            when (state.sidePanel) {
                SidePanel.QUEUE -> QueueList(state)
                SidePanel.LYRICS -> Lyrics(state)
                SidePanel.NONE -> Unit
            }
        }
    }
}

@Composable
private fun Tab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) VibyColors.TextPrimary else VibyColors.TextMuted,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        Box(
            Modifier.width(36.dp).height(2.dp)
                .background(if (selected) VibyColors.TextPrimary else Color.Transparent)
        )
    }
}

@Composable
private fun QueueList(state: AppState) {
    val player = state.player
    val listState = rememberLazyListState()
    // keep the playing track at the top when it changes, like the Android queue panel
    LaunchedEffect(player.index) {
        if (player.index >= 0) listState.animateScrollToItem(player.index)
    }
    if (player.queue.isEmpty()) {
        EmptyHint("Очередь пуста", "Запустите плейлист или трек — здесь появится то, что играет дальше.")
        return
    }
    var dragging by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }
    val step = with(LocalDensity.current) { QueueRowHeight.toPx() }
    Column(Modifier.fillMaxSize()) {
        if (player.customized || player.shuffle) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (player.shuffle) "Очередь перемешана" else "Очередь изменена",
                    style = MaterialTheme.typography.bodySmall,
                    color = VibyColors.TextMuted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = state::resetQueue) { Text("Сбросить очередь") }
            }
        }
        ScrollLazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp)) {
            itemsIndexed(player.queue, key = { index, track -> "$index-${track.id}" }) { index, track ->
                QueueItem(
                    track = track,
                    current = index == player.index,
                    past = index < player.index,
                    onClick = { player.jumpTo(index) },
                    onRemove = { player.removeFromQueue(index) },
                    onDrag = { dy ->
                        dragging = index
                        dragOffset += dy
                    },
                    onDragEnd = {
                        val to = (index + (dragOffset / step).roundToInt()).coerceIn(0, player.queue.lastIndex)
                        player.moveInQueue(index, to)
                        dragging = -1
                        dragOffset = 0f
                    },
                    modifier = Modifier
                        .zIndex(if (dragging == index) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging == index) dragOffset else 0f },
                )
            }
        }
    }
}

private val QueueRowHeight = 60.dp

@Composable
private fun QueueItem(
    track: Track,
    current: Boolean,
    past: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .fillMaxWidth()
            .height(QueueRowHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    current -> VibyColors.Selected
                    hovered -> VibyColors.Hover
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DragHandle(onDrag, onDragEnd)
        Spacer(Modifier.width(4.dp))
        CoverArt(track.id, 44.dp, imageUrl = track.thumbnailUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    current -> VibyColors.AccentBright
                    past -> VibyColors.TextMuted
                    else -> VibyColors.TextPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = VibyColors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (hovered && !current) {
            IconAction(Icons.Rounded.Close, "Убрать из очереди", onRemove, size = 28.dp, iconSize = 18.dp)
        }
    }
}

@Composable
private fun Lyrics(state: AppState) {
    val track = state.player.current
    if (track == null) {
        EmptyHint("Ничего не играет", "Текст песни появится здесь во время воспроизведения.")
        return
    }
    val (top, bottom) = coverColors(track.id)
    val result by produceState<LyricsResult>(LyricsResult.Loading, track.id) {
        value = withContext(Dispatchers.IO) { loadLyrics(state, track) }
    }
    val background = Modifier
        .fillMaxSize()
        .padding(12.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(Brush.verticalGradient(listOf(top.copy(alpha = 0.85f), bottom)))
    val found = result as? LyricsResult.Found
    if (found != null && found.lines.isNotEmpty()) {
        SyncedLyrics(state, track, found, background)
        return
    }
    ScrollColumn(
        background,
        contentModifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(track.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Text(track.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(12.dp))
        when (val current = result) {
            LyricsResult.Loading -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp))
            is LyricsResult.Missing -> Text(current.message, color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyLarge)
            is LyricsResult.Found -> {
                Text(
                    current.text,
                    color = Color.White,
                    fontSize = 20.sp,
                    lineHeight = 31.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))
                Text(current.source, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Karaoke view: the line being sung is bright and kept in the middle, sung lines dim, the rest
 * waits; a click on a line jumps there.
 */
@Composable
private fun SyncedLyrics(state: AppState, track: Track, lyrics: LyricsResult.Found, modifier: Modifier) {
    val lines = lyrics.lines
    // lines change a little before the voice: reading lags behind the sound
    val position = state.player.positionMs + LYRICS_LEAD_MS
    val currentLine = lines.indexOfLast { it.timeMs <= position }
    val listState = rememberLazyListState()
    LaunchedEffect(track.id, currentLine) {
        // the header is item 0; keep the sung line about a third from the top
        val target = (currentLine + 1).coerceAtLeast(0)
        val anchor = listState.layoutInfo.viewportSize.height / 3
        val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
        if (visible != null) {
            // the next line is on screen: one smooth glide from where the list is now
            listState.animateScrollBy((visible.offset - anchor).toFloat(), tween(LYRICS_SCROLL_MS, easing = FastOutSlowInEasing))
        } else {
            // a seek or the first show: no long animated run through the whole text
            listState.scrollToItem(target)
            // measured only now: on the first show the panel has no size yet
            listState.scrollBy(-listState.layoutInfo.viewportSize.height / 3f)
        }
    }
    LazyColumn(modifier, state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
        item {
            Column(Modifier.padding(bottom = 16.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            }
        }
        itemsIndexed(lines) { index, line ->
            // only the brightness changes: a bigger font would re-wrap the line and shift the text below
            val alpha by animateFloatAsState(
                when {
                    index == currentLine -> 1f
                    index < currentLine -> 0.45f
                    else -> 0.7f
                },
                tween(LYRICS_SCROLL_MS),
            )
            Text(
                line.text().ifBlank { "♪" },
                color = Color.White.copy(alpha = alpha),
                fontSize = 20.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { state.player.seekToMs(line.timeMs()) }
                    .padding(vertical = 6.dp),
            )
        }
        item {
            Text(lyrics.source, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 16.dp))
        }
    }
}

/** Synced lines switch this much before their time stamp. */
private const val LYRICS_LEAD_MS = 250L
/** Glide to the next line and fade of the highlight. */
private const val LYRICS_SCROLL_MS = 450

private sealed interface LyricsResult {
    data object Loading : LyricsResult
    data class Found(val text: String, val source: String, val lines: List<TimedLine> = emptyList()) : LyricsResult
    data class Missing(val message: String) : LyricsResult
}

/** Synced lyrics from LRCLIB first, then YouTube Music, then the video's subtitles. */
private fun loadLyrics(state: AppState, track: Track): LyricsResult = try {
    val cookies = state.settings.cookieSource().takeIf { it.type() == CookieSource.Type.FILE }?.file()
    val lyrics = YoutubeLyricsRepository.load(cookies, track.videoId, track.sourceUrl, track.title, track.artist,
        track.durationMs)
    val source = when (lyrics.source) {
        YoutubeLyricsRepository.Source.LRCLIB -> if (lyrics.isSynced) "LRCLIB · синхронный текст" else "LRCLIB"
        YoutubeLyricsRepository.Source.YOUTUBE_MUSIC -> lyrics.detail ?: "YouTube Music"
        YoutubeLyricsRepository.Source.MANUAL_SUBTITLES -> "Субтитры видео (${lyrics.detail})"
        YoutubeLyricsRepository.Source.AUTOMATIC_SUBTITLES -> "Автоматические субтитры (${lyrics.detail})"
        null -> ""
    }
    LyricsResult.Found(lyrics.text, source, lyrics.lines)
} catch (e: YoutubeLyricsRepository.MissingLyricsException) {
    LyricsResult.Missing(
        if (e.reason == YoutubeLyricsRepository.MissingReason.NO_YOUTUBE_SOURCE) "Трек не связан с YouTube — текст взять неоткуда"
        else "Для этого трека текста нет ни в LRCLIB, ни в YouTube Music, ни в субтитрах"
    )
} catch (e: Throwable) {
    // lyrics are optional: no failure here (network, YouTube changes, linkage errors) may close the app
    // only recognised causes are named; internal errors (parsing, YouTube changes) stay out of the panel
    val reason = ErrorText.known(e.message ?: e.javaClass.simpleName)
    LyricsResult.Missing(reason?.let { "Не удалось загрузить текст: $it" } ?: "Не удалось загрузить текст — попробуйте позже")
}

@Composable
fun EmptyHint(title: String, text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = VibyColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = VibyColors.TextSecondary)
    }
}
