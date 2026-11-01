package com.viby.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.viby.app.state.AppState
import com.viby.app.state.Screen
import com.viby.app.state.SidePanel
import com.viby.app.theme.VibyColors
import com.viby.app.theme.VibyTheme
import com.viby.app.ui.IconAction
import com.viby.app.ui.Panel
import com.viby.app.ui.PlayerBar
import com.viby.app.ui.RightPanel
import com.viby.app.ui.Sidebar
import com.viby.app.ui.screens.AddScreen
import com.viby.app.ui.screens.DownloadsScreen
import com.viby.app.ui.screens.HomeScreen
import com.viby.app.ui.screens.PlaylistScreen
import com.viby.app.ui.screens.SettingsScreen
import kotlinx.coroutines.delay
import java.awt.Dimension

fun main() = application {
    val state = remember { AppState() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Viby",
        state = rememberWindowState(size = DpSize(1360.dp, 860.dp)),
        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) return@Window false
            when {
                event.key == Key.Spacebar -> state.player.togglePlay()
                event.isCtrlPressed && event.key == Key.DirectionRight -> state.player.next()
                event.isCtrlPressed && event.key == Key.DirectionLeft -> state.player.previous()
                event.isCtrlPressed && event.key == Key.L -> state.toggleSidePanel(SidePanel.LYRICS)
                event.isCtrlPressed && event.key == Key.Q -> state.toggleSidePanel(SidePanel.QUEUE)
                else -> return@Window false
            }
            true
        },
    ) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(1000, 640) }
        LaunchedEffect(Unit) { state.player.runClock() }
        LaunchedEffect(Unit) { state.downloads.runSimulation() }
        VibyTheme { App(state) }
    }
}

@Composable
fun App(state: AppState) {
    Box(Modifier.fillMaxSize().background(VibyColors.Background)) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Row(Modifier.weight(1f)) {
                Sidebar(state)
                Spacer(Modifier.width(8.dp))
                Panel(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.fillMaxSize()) {
                        when (val screen = state.screen) {
                            Screen.Home -> HomeScreen(state)
                            Screen.Add -> AddScreen(state)
                            Screen.Downloads -> DownloadsScreen(state)
                            Screen.Settings -> SettingsScreen(state)
                            is Screen.PlaylistScreen -> PlaylistScreen(state, screen.id)
                        }
                        if (state.canGoBack) {
                            Box(
                                Modifier.padding(12.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                            ) {
                                IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", state::back,
                                    tint = VibyColors.TextPrimary)
                            }
                        }
                    }
                }
                if (state.sidePanel != SidePanel.NONE) {
                    Spacer(Modifier.width(8.dp))
                    RightPanel(state)
                }
            }
            PlayerBar(state)
        }
        Toast(state, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
    }
}

@Composable
private fun Toast(state: AppState, modifier: Modifier) {
    val message = state.message ?: return
    LaunchedEffect(message) {
        delay(3_000)
        state.consumeMessage()
    }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(VibyColors.TextPrimary)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(message, color = Color.Black, style = MaterialTheme.typography.bodyMedium)
    }
}
