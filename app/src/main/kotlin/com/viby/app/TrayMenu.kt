package com.viby.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.viby.app.state.AppState
import com.viby.app.theme.VibyColors
import com.viby.app.theme.VibyTheme
import com.viby.app.ui.rememberCover
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.imageio.ImageIO

private val MENU_SIZE = DpSize(300.dp, 236.dp)

/**
 * The tray icon. Its menu is a small Viby-styled window (the native Windows menu cannot take the
 * app's colours): the playing track, playback buttons in the accent colour, "show" and "exit".
 * Returns the function that shows a balloon notification, or null when there is no tray.
 */
@Composable
fun VibyTray(state: AppState, onShow: () -> Unit, onQuit: () -> Unit): ((String, String) -> Unit)? {
    if (!remember { SystemTray.isSupported() }) return null
    var menuAt by remember { mutableStateOf<Point?>(null) }
    val icon = remember {
        TrayIcon(ImageIO.read(AppState::class.java.getResource("/viby_icon.png")), "Viby").apply {
            isImageAutoSize = true
        }
    }
    DisposableEffect(icon) {
        val mouse = object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) menuAt = e.locationOnScreen
            }
        }
        icon.addMouseListener(mouse)
        // a double click (Windows) or a click (Linux) on the icon
        icon.addActionListener { menuAt = null; onShow() }
        SystemTray.getSystemTray().add(icon)
        onDispose { SystemTray.getSystemTray().remove(icon) }
    }
    val current = state.player.current
    LaunchedEffect(current) {
        icon.toolTip = current?.let { "Viby – ${it.title}".take(120) } ?: "Viby"
    }
    menuAt?.let { point ->
        TrayMenuWindow(state, point, onClose = { menuAt = null }, onShow = onShow, onQuit = onQuit)
    }
    return remember(icon) { { title, text -> icon.displayMessage(title, text, TrayIcon.MessageType.NONE) } }
}

/** Places the menu next to the cursor, inside the usable part of its screen (not over the taskbar). */
private fun menuPosition(point: Point): WindowPosition {
    val config = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .map { it.defaultConfiguration }
        .firstOrNull { it.bounds.contains(point) }
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    val b = config.bounds
    val width = MENU_SIZE.width.value.toInt()
    val height = MENU_SIZE.height.value.toInt()
    val left = b.x + insets.left
    val top = b.y + insets.top
    val right = b.x + b.width - insets.right
    val bottom = b.y + b.height - insets.bottom
    val x = (point.x - width).coerceIn(left, (right - width).coerceAtLeast(left))
    // above the cursor when the taskbar is at the bottom, below it when it is at the top
    val y = (if (point.y - height >= top) point.y - height else point.y).coerceIn(top, (bottom - height).coerceAtLeast(top))
    return WindowPosition(x.dp, y.dp)
}

@Composable
private fun TrayMenuWindow(
    state: AppState,
    point: Point,
    onClose: () -> Unit,
    onShow: () -> Unit,
    onQuit: () -> Unit,
) {
    val dialogState = rememberDialogState(position = menuPosition(point), size = MENU_SIZE)
    DialogWindow(
        onCloseRequest = onClose,
        state = dialogState,
        title = "Viby",
        undecorated = true,
        transparent = true,
        resizable = false,
        onKeyEvent = { event -> if (event.key == Key.Escape) { onClose(); true } else false },
    ) {
        LaunchedEffect(Unit) {
            window.isAlwaysOnTop = true
            // a click anywhere else closes the menu, like a normal one
            window.addWindowFocusListener(object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) = onClose()
            })
            window.toFront()
            window.requestFocus()
        }
        VibyTheme {
            TrayMenuContent(
                state,
                onShow = { onClose(); onShow() },
                onQuit = { onClose(); onQuit() },
            )
        }
    }
}

@Composable
internal fun TrayMenuContent(state: AppState, onShow: () -> Unit, onQuit: () -> Unit) {
    val player = state.player
    val track = player.current
    Column(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(VibyColors.Elevated)
            .border(1.dp, VibyColors.Divider, RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            val cover = rememberCover(track?.thumbnailUrl)
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(VibyColors.Highlight),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) {
                    Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Rounded.MusicNote, null, tint = VibyColors.TextMuted)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track?.title ?: "Ничего не играет",
                    style = MaterialTheme.typography.titleSmall,
                    color = VibyColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (track != null) {
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = VibyColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val enabled = track != null
            RoundButton(Icons.Rounded.SkipPrevious, "Предыдущий трек", enabled, 40, Color.Transparent, VibyColors.TextPrimary) {
                player.previous()
            }
            RoundButton(
                if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (player.isPlaying) "Пауза" else "Играть",
                enabled, 48, VibyColors.Accent, VibyColors.OnAccent,
            ) { player.togglePlay() }
            RoundButton(Icons.Rounded.SkipNext, "Следующий трек", enabled, 40, Color.Transparent, VibyColors.TextPrimary) {
                player.next()
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(VibyColors.Divider))
        Spacer(Modifier.height(4.dp))
        MenuRow(Icons.Rounded.OpenInNew, "Показать Viby", onShow)
        MenuRow(Icons.AutoMirrored.Rounded.ExitToApp, "Выход", onQuit)
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    size: Int,
    background: Color,
    tint: Color,
    onClick: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        Modifier.size(size.dp)
            .clip(CircleShape)
            .background(if (background == Color.Transparent && hovered && enabled) VibyColors.Hover else background)
            .hoverable(hover)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size((size * 0.6f).dp))
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) VibyColors.Hover else Color.Transparent)
            .hoverable(hover)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (hovered) VibyColors.AccentBright else VibyColors.TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = VibyColors.TextPrimary)
    }
}
