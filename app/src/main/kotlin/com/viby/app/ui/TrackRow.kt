package com.viby.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viby.app.model.Track
import com.viby.app.theme.VibyColors
import com.viby.core.util.Formats
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ru"))

enum class ClickKind { PLAIN, TOGGLE, RANGE }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    number: Int,
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    selected: Boolean,
    onClick: (ClickKind) -> Unit,
    onPlay: () -> Unit,
    /** Pause/resume for the track that is already loaded; the hover button must not restart it. */
    onTogglePlay: () -> Unit = onPlay,
    modifier: Modifier = Modifier,
    showAdded: Boolean = true,
    /** Shown after the artist, e.g. the playlists of a search result. */
    context: String? = null,
    favorite: Boolean = false,
    /** Heart button (Избранное); null hides it. */
    onFavorite: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    selected -> VibyColors.Selected
                    hovered -> VibyColors.Hover
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            .onClick(keyboardModifiers = { isCtrlPressed || isMetaPressed }) { onClick(ClickKind.TOGGLE) }
            .onClick(keyboardModifiers = { isShiftPressed }) { onClick(ClickKind.RANGE) }
            .onClick(onDoubleClick = onPlay) { onClick(ClickKind.PLAIN) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
            when {
                hovered && isCurrent -> IconAction(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (isPlaying) "Пауза" else "Продолжить", onTogglePlay, size = 24.dp,
                    tint = VibyColors.AccentBright,
                )
                hovered -> IconAction(Icons.Rounded.PlayArrow, "Играть", onPlay, size = 24.dp,
                    tint = VibyColors.TextPrimary)
                isCurrent && isPlaying -> Icon(Icons.Rounded.Equalizer, null, tint = VibyColors.AccentBright,
                    modifier = Modifier.size(18.dp))
                else -> Text(
                    "$number",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isCurrent) VibyColors.AccentBright else VibyColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        CoverArt(track.id, 40.dp, downloadProgress = track.downloadProgress,
            imageUrl = track.thumbnailUrl, failed = track.failed != null || track.fileMissing)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) VibyColors.AccentBright else VibyColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    track.artist,
                    context,
                    when {
                        track.failed != null -> track.failed
                        track.fileMissing -> "файл не найден"
                        track.isDownloaded -> null
                        else -> "загрузка ${track.downloadProgress}%"
                    },
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = VibyColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onFavorite != null) {
            // always visible once liked; otherwise it appears with the pointer, like the play button
            Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                if (favorite || hovered) {
                    IconAction(
                        if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (favorite) "Убрать из избранного" else "В избранное",
                        onFavorite, size = 28.dp, iconSize = 18.dp, active = favorite,
                    )
                }
            }
        }
        if (showAdded) {
            Text(
                if (track.addedAt > 0) dateFormat.format(Instant.ofEpochMilli(track.addedAt).atZone(ZoneId.systemDefault())) else "",
                style = MaterialTheme.typography.bodySmall,
                color = VibyColors.TextSecondary,
                modifier = Modifier.width(140.dp).padding(start = 16.dp),
            )
        }
        Text(
            Formats.duration(track.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = VibyColors.TextSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
    }
}
