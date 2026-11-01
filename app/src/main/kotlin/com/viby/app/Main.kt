package com.viby.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.viby.core.platform.Platform

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Viby",
        state = rememberWindowState(size = DpSize(1200.dp, 800.dp)),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Box(contentAlignment = Alignment.Center) {
                    Text("Viby — ${Platform.current()}")
                }
            }
        }
    }
}
