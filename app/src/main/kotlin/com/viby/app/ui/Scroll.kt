package com.viby.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** Scrollable area with a draggable scrollbar and browser-style middle-click autoscroll. */
@Composable
fun ScrollArea(
    state: ScrollState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) = ScrollArea(state, rememberScrollbarAdapter(state), modifier, content)

@Composable
fun ScrollArea(
    state: LazyListState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) = ScrollArea(state, rememberScrollbarAdapter(state), modifier, content)

@Composable
private fun ScrollArea(
    scrollable: ScrollableState,
    adapter: ScrollbarAdapter,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var anchor by remember { mutableStateOf<Offset?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }

    // scroll speed grows with the distance from the anchor, like Chrome/Firefox autoscroll
    LaunchedEffect(anchor) {
        val start = anchor ?: return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val seconds = (now - last) / 1_000_000_000f
            last = now
            val distance = pointer.y - start.y
            val active = abs(distance) - DEAD_ZONE_PX
            if (active > 0) scrollable.scrollBy(sign(distance) * active * SPEED * seconds)
        }
    }

    Box(
        modifier.pointerInput(Unit) {
            var held = false
            var moved = false
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val position = event.changes.first().position
                    when (event.type) {
                        PointerEventType.Press -> when {
                            // any click while autoscrolling only leaves the mode
                            anchor != null -> {
                                anchor = null
                                event.changes.forEach { it.consume() }
                            }
                            event.buttons.isTertiaryPressed -> {
                                anchor = position
                                pointer = position
                                held = true
                                moved = false
                                event.changes.forEach { it.consume() }
                            }
                        }
                        PointerEventType.Move -> if (anchor != null) {
                            pointer = position
                            if ((position - anchor!!).getDistance() > DEAD_ZONE_PX) moved = true
                        }
                        PointerEventType.Release -> if (held && !event.buttons.isTertiaryPressed) {
                            held = false
                            // press-drag-release scrolls only while held; a plain click toggles the mode
                            if (moved) anchor = null
                        }
                    }
                }
            }
        },
    ) {
        content()
        VerticalScrollbar(
            adapter,
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp),
        )
        anchor?.let { AutoScrollAnchor(it) }
    }
}

@Composable
private fun AutoScrollAnchor(position: Offset) {
    val half = with(LocalDensity.current) { 14.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset((position.x - half).roundToInt(), (position.y - half).roundToInt()) }
            .size(28.dp)
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.UnfoldMore, "Автопрокрутка", tint = Color.Black, modifier = Modifier.size(20.dp))
    }
}

/** LazyColumn inside a [ScrollArea]. */
@Composable
fun ScrollLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) = ScrollArea(state, modifier) {
    LazyColumn(Modifier.fillMaxSize(), state, contentPadding, verticalArrangement = verticalArrangement,
        content = content)
}

/**
 * Vertically scrolling Column inside a [ScrollArea]. [modifier] sizes the area (and may draw
 * its background); [contentModifier] applies inside the scrolling content, e.g. padding.
 */
@Composable
fun ScrollColumn(
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberScrollState()
    ScrollArea(state, modifier) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(state).then(contentModifier),
            verticalArrangement,
            horizontalAlignment,
            content,
        )
    }
}

private const val DEAD_ZONE_PX = 12f
/** Pixels per second per pixel of distance from the anchor. */
private const val SPEED = 6f
