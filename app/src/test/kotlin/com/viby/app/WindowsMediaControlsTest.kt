package com.viby.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Frame
import java.io.File
import java.util.UUID

class WindowsMediaControlsTest {

    /** Reference value from the WinRT spec: IVector<String>. */
    @Test
    fun parameterizedIidMatchesTheWinRtAlgorithm() {
        assertEquals(
            UUID.fromString("98b9acc1-4b56-532e-ac73-03d5291cca90"),
            WindowsMediaControls.parameterizedIid("pinterface({913337e9-11a1-4345-a3a2-4e7f956e222d};string)"),
        )
    }

    /** Real SMTC registration on a window that is created but never shown (no focus stealing). */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
    fun registersAMediaSessionForAWindow() {
        val log = File("build/tmp/smtc-${System.nanoTime()}.log").apply { parentFile.mkdirs() }
        val frame = Frame("Viby SMTC test").apply { addNotify() }
        try {
            val controls = WindowsMediaControls.create(frame, log) { }
            assertNotNull(controls, log.takeIf { it.exists() }?.readText())
            controls!!.update("Char", "Crystal Castles", null, playing = true)
            controls.update("Char", "Crystal Castles", "https://i.ytimg.com/vi/KLBj26tRFS8/mqdefault.jpg", playing = false)
            controls.close()
            Thread.sleep(1500)
            val text = log.readText()
            assert("failed" !in text) { text }
        } finally {
            frame.dispose()
        }
    }
}
