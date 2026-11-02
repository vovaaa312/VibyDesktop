package com.viby.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.viby.app.theme.VibyColors

/** One entry of a [ContextMenuBox]. */
class MenuAction(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * Right-click menu styled like the Android track sheet: a rounded card with an optional title.
 * Replaces Compose's ContextMenuArea so the look matches the app and stays inside the window.
 */
@Composable
fun ContextMenuBox(
    header: String?,
    actions: () -> List<MenuAction>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var openAt by remember { mutableStateOf<IntOffset?>(null) }
    Box(
        modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.type != PointerEventType.Press || !event.buttons.isSecondaryPressed) continue
                    val position = event.changes.firstOrNull()?.position ?: continue
                    openAt = IntOffset(position.x.toInt(), position.y.toInt())
                    event.changes.forEach { it.consume() }
                }
            }
        },
    ) {
        content()
        openAt?.let { offset ->
            Popup(
                popupPositionProvider = FitInWindow(offset),
                onDismissRequest = { openAt = null },
                properties = PopupProperties(focusable = true),
            ) {
                MenuCard(header, actions()) { openAt = null }
            }
        }
    }
}

@Composable
private fun MenuCard(header: String?, actions: List<MenuAction>, close: () -> Unit) {
    Column(
        Modifier
            .width(300.dp)
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(MenuBackground)
            .padding(vertical = 10.dp),
    ) {
        if (header != null) {
            Text(
                header,
                color = VibyColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp),
            )
        }
        actions.forEach { action ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Text(
                action.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (action.danger) VibyColors.Danger else VibyColors.TextPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (hovered) VibyColors.Hover else Color.Transparent)
                    .hoverable(interaction)
                    .clickable(interactionSource = interaction, indication = null) {
                        close()
                        action.onClick()
                    }
                    .padding(horizontal = 20.dp, vertical = 11.dp),
            )
        }
    }
}

/** Opens at the click point and flips up/left when the card would leave the window. */
private class FitInWindow(private val click: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        var x = anchorBounds.left + click.x
        var y = anchorBounds.top + click.y
        if (x + popupContentSize.width > windowSize.width) x -= popupContentSize.width
        if (y + popupContentSize.height > windowSize.height) y -= popupContentSize.height
        return IntOffset(
            x.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width)),
            y.coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height)),
        )
    }
}

private val MenuBackground = Color(0xFF2B2B31)
