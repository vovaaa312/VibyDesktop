package com.viby.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.viby.app.theme.VibyTheme
import com.viby.app.ui.ContextMenuBox
import com.viby.app.ui.MenuAction
import com.viby.app.ui.ScrollLazyColumn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Right-click menus inside scroll areas: open, pick an action, close. */
@OptIn(ExperimentalComposeUiApi::class)
class ScrollAreaTest {

    @Test
    fun rightClickOpensMenuAndRunsAction() {
        var clicked = ""
        val scene = ImageComposeScene(500, 600, Density(1f)) {
            VibyTheme {
                ScrollLazyColumn(Modifier.fillMaxSize()) {
                    items((1..50).toList()) { index ->
                        ContextMenuBox(header = "Трек $index", actions = {
                            listOf(MenuAction("Воспроизвести") { clicked = "play $index" })
                        }) {
                            Text("Трек $index")
                        }
                    }
                }
            }
        }
        scene.render(0)
        click(scene, Offset(20f, 8f), PointerButton.Secondary)
        scene.render(100_000_000)
        // the card opens at the click point: header first, then the action
        click(scene, Offset(60f, 70f), PointerButton.Primary)
        scene.render(200_000_000)
        scene.close()
        assertEquals("play 1", clicked)
    }

    private fun click(scene: ImageComposeScene, at: Offset, button: PointerButton) {
        val pressed = if (button == PointerButton.Secondary) PointerButtons(isSecondaryPressed = true)
        else PointerButtons(isPrimaryPressed = true)
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.sendPointerEvent(PointerEventType.Press, at, buttons = pressed, button = button)
        scene.sendPointerEvent(PointerEventType.Release, at, button = button)
    }
}
