package com.viby.app

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Frame

class WindowsTitleBarTest {

    /** DWM accepts the dark caption on a window that is created but never shown. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
    fun darkCaptionIsAccepted() {
        val frame = Frame("Viby title bar test").apply { addNotify() }
        try {
            assertTrue(WindowsTitleBar.apply(frame, caption = 0x000000, text = 0xFFFFFF))
        } finally {
            frame.dispose()
        }
    }
}
