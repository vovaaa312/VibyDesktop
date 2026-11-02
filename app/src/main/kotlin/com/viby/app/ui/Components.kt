package com.viby.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.viby.app.theme.VibyColors
import kotlin.math.absoluteValue

/**
 * Square cover: the YouTube thumbnail cropped to the center, or a deterministic gradient while
 * it loads or when there is none. [failed] shows a warning overlay for failed downloads.
 */
@Composable
fun CoverArt(
    seed: String,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 4.dp,
    downloadProgress: Int? = null,
    imageUrl: String? = null,
    failed: Boolean = false,
) {
    val (top, bottom) = coverColors(seed)
    val image = rememberCover(imageUrl)
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(top, bottom))),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(size * 0.42f),
            )
        }
        if (failed) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
            Icon(Icons.Rounded.ErrorOutline, "Ошибка загрузки", tint = VibyColors.Danger,
                modifier = Modifier.size(size * 0.5f))
        } else if (downloadProgress != null) {
            // same idea as Android's DownloadProgressOverlayView: darkened art + ring
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
            CircularProgressIndicator(
                progress = { downloadProgress / 100f },
                modifier = Modifier.size(size * 0.55f),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.2f),
                strokeWidth = (size.value / 18).coerceIn(2f, 4f).dp,
            )
        }
    }
}

fun coverColors(seed: String): Pair<Color, Color> =
    VibyColors.CoverGradients[seed.hashCode().absoluteValue % VibyColors.CoverGradients.size]

/** Icon button with hover brightening, used throughout the player and lists. */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    iconSize: Dp = 20.dp,
    tint: Color = VibyColors.TextSecondary,
    active: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = when {
                active -> VibyColors.AccentBright
                hovered -> VibyColors.TextPrimary
                else -> tint
            },
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Large filled circular play button (Spotify style). */
@Composable
fun PlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    size: Dp = 56.dp,
    background: Color = VibyColors.Accent,
    iconTint: Color = Color.Black,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                when {
                    !hovered -> background
                    background == VibyColors.Accent -> VibyColors.AccentBright
                    else -> background.copy(alpha = 0.85f)
                }
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Rounded.Pause
            else Icons.Rounded.PlayArrow,
            contentDescription = if (playing) "Пауза" else "Играть",
            tint = iconTint,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/**
 * Thin progress bar that thickens and shows a knob on hover; click or drag to seek.
 * Reports the fraction only on release so a drag does not spam the player.
 */
@Composable
fun SeekBar(
    fraction: Float,
    onSeek: (Float) -> Unit,
    /** Report every drag step (volume) instead of only the release (track seeking). */
    live: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    // the gesture detectors below start once; they must call the latest callback
    val currentOnSeek by rememberUpdatedState(onSeek)
    val shown = (dragFraction ?: fraction).coerceIn(0f, 1f)
    val active = hovered || dragFraction != null
    Canvas(
        modifier
            .fillMaxWidth()
            .height(14.dp)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectTapGestures { offset -> currentOnSeek(offset.x / size.width) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragFraction = it.x / size.width
                        if (live) currentOnSeek(it.x / size.width)
                    },
                    onDragEnd = { dragFraction?.let(currentOnSeek); dragFraction = null },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    dragFraction = fraction
                    if (live) currentOnSeek(fraction)
                }
            },
    ) {
        val barHeight = 4.dp.toPx()
        val top = (size.height - barHeight) / 2
        val radius = CornerRadius(barHeight / 2)
        drawRoundRect(Color(0xFF4D4D4D), Offset(0f, top), Size(size.width, barHeight), radius)
        drawRoundRect(
            if (active) VibyColors.Accent else Color.White,
            Offset(0f, top),
            Size(size.width * shown, barHeight),
            radius,
        )
        if (active) {
            drawCircle(Color.White, 6.dp.toPx(), Offset(size.width * shown, size.height / 2))
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
        color = VibyColors.TextPrimary,
        modifier = modifier.padding(bottom = 12.dp),
    )
}

/** Grip for drag-to-reorder lists; reports vertical drag deltas. */
@Composable
fun DragHandle(onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    // the detector starts once per handle: without these a drop would use the list as it was
    // when the row first appeared (e.g. a track that has started downloading since)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    Box(
        Modifier
            .size(24.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { currentOnDragEnd() }, onDragCancel = { currentOnDragEnd() }) { change, drag ->
                    change.consume()
                    currentOnDrag(drag.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.DragIndicator, "Перетащить", tint = VibyColors.TextMuted, modifier = Modifier.size(20.dp))
    }
}
