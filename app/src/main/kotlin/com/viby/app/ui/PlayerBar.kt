package com.viby.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.viby.app.state.PlayerState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viby.app.model.RepeatMode
import com.viby.app.state.AppState
import com.viby.app.state.Screen
import com.viby.app.state.SidePanel
import com.viby.app.state.SleepTimer
import com.viby.app.theme.VibyColors
import com.viby.core.util.Formats

@Composable
fun PlayerBar(state: AppState, modifier: Modifier = Modifier) {
    val player = state.player
    val track = player.current
    Row(
        modifier.fillMaxWidth().height(80.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // now playing
        Row(Modifier.weight(0.3f), verticalAlignment = Alignment.CenterVertically) {
            if (track != null) {
                CoverArt(track.id, 56.dp, imageUrl = track.thumbnailUrl)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.widthIn(max = 260.dp)) {
                    Text(track.title, style = MaterialTheme.typography.bodyMedium, color = VibyColors.TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = VibyColors.TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                val liked = state.favorites.isFavorite(track)
                IconAction(
                    if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (liked) "Убрать из избранного" else "В избранное",
                    { state.favorites.toggle(track) }, active = liked,
                )
            }
        }

        // transport
        Column(Modifier.weight(0.4f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconAction(Icons.Rounded.Shuffle, "Перемешать", player::toggleShuffle, active = player.shuffle)
                IconAction(Icons.Rounded.SkipPrevious, "Предыдущий", player::previous, iconSize = 26.dp)
                PlayButton(player.isPlaying, player::togglePlay, size = 36.dp,
                    background = Color.White, iconTint = Color.Black)
                IconAction(Icons.Rounded.SkipNext, "Следующий", { player.next() }, iconSize = 26.dp)
                IconAction(
                    if (player.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Повтор", player::cycleRepeat, active = player.repeat != RepeatMode.OFF,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val duration = track?.durationMs ?: 0
                TimeLabel(Formats.duration(player.positionMs), TextAlign.End)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    SeekBar(
                        fraction = if (duration > 0) player.positionMs.toFloat() / duration else 0f,
                        onSeek = player::seek,
                    )
                }
                TimeLabel(Formats.duration(duration), TextAlign.Start)
            }
        }

        // side panels + volume
        Row(
            Modifier.weight(0.3f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SleepTimerButton(state.sleepTimer)
            IconAction(Icons.Rounded.GraphicEq, "Эквалайзер", { state.navigate(Screen.Equalizer) },
                active = state.engine.equalizer().isEnabled)
            IconAction(Icons.Rounded.Lyrics, "Текст", { state.toggleSidePanel(SidePanel.LYRICS) },
                active = state.sidePanel == SidePanel.LYRICS)
            IconAction(Icons.AutoMirrored.Rounded.QueueMusic, "Очередь", { state.toggleSidePanel(SidePanel.QUEUE) },
                active = state.sidePanel == SidePanel.QUEUE)
            // the mouse wheel over the icon or the slider changes the volume
            Row(Modifier.volumeWheel(player), verticalAlignment = Alignment.CenterVertically) {
                IconAction(
                    when {
                        player.muted || player.volume == 0f -> Icons.AutoMirrored.Rounded.VolumeOff
                        player.volume < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
                        else -> Icons.AutoMirrored.Rounded.VolumeUp
                    },
                    "Звук", { player.muted = !player.muted },
                )
                Box(Modifier.width(110.dp).padding(end = 8.dp)) {
                    SeekBar(if (player.muted) 0f else player.volume, live = true, onSeek = {
                        player.volume = it.coerceIn(0f, 1f)
                        player.muted = false
                    })
                }
            }
        }
    }
}

/** Moon button: pick a sleep timer, or see what is left and switch it off. */
@Composable
private fun SleepTimerButton(timer: SleepTimer) {
    var open by remember { mutableStateOf(false) }
    val left = timer.remainingMs
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (left != null) {
                Text(Formats.duration(left), style = MaterialTheme.typography.labelMedium, color = VibyColors.AccentBright)
            }
            IconAction(
                Icons.Rounded.Bedtime,
                when {
                    left != null -> "Таймер сна: осталось ${Formats.duration(left)}"
                    timer.untilTrackEnds -> "Таймер сна: до конца трека"
                    else -> "Таймер сна"
                },
                { open = true }, active = timer.active,
            )
        }
        DropdownMenu(open, { open = false }, containerColor = VibyColors.Highlight) {
            Text(
                when {
                    left != null -> "Остановка через ${Formats.duration(left)}"
                    timer.untilTrackEnds -> "Остановка после этого трека"
                    else -> "Остановить музыку через…"
                },
                style = MaterialTheme.typography.labelSmall, color = VibyColors.TextMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            SleepTimer.PRESETS_MINUTES.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(if (minutes < 60) "$minutes минут" else if (minutes == 60) "1 час" else "1,5 часа", color = VibyColors.TextPrimary) },
                    onClick = { timer.start(minutes); open = false },
                )
            }
            DropdownMenuItem(
                text = { Text("До конца трека", color = if (timer.untilTrackEnds) VibyColors.AccentBright else VibyColors.TextPrimary) },
                onClick = { timer.stopAfterTrack(); open = false },
            )
            if (timer.active) {
                DropdownMenuItem(
                    text = { Text("Выключить таймер", color = VibyColors.Danger) },
                    onClick = { timer.cancel(); open = false },
                )
            }
        }
    }
}

/** One wheel notch = 5 % of the volume; scrolling up is louder. */
private fun Modifier.volumeWheel(player: PlayerState): Modifier = pointerInput(player) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll) continue
            val delta = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
            if (delta != 0f) {
                val base = if (player.muted) 0f else player.volume
                player.volume = (base - delta * VOLUME_WHEEL_STEP).coerceIn(0f, 1f)
                event.changes.forEach { it.consume() }
            }
        }
    }
}

private const val VOLUME_WHEEL_STEP = 0.05f

@Composable
private fun TimeLabel(text: String, align: TextAlign) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = VibyColors.TextSecondary,
        textAlign = align,
        modifier = Modifier.width(44.dp),
    )
}
