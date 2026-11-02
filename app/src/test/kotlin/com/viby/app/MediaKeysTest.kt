package com.viby.app

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File

class MediaKeysTest {

    /** The installed app cannot write next to its jar (Program Files), so the DLL goes to app data. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    // CI runners have no interactive desktop to hook
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
    fun hookLibraryIsExtractedIntoTheGivenFolder() {
        // not @TempDir: the loaded DLL stays locked until the JVM exits and cannot be cleaned up
        val temp = File("build/tmp/media-keys-${System.nanoTime()}")
        val native = File(temp, "native")
        val log = File(temp, "media-keys.log").also { temp.mkdirs() }
        try {
            assertTrue(MediaKeys.register(log, native) { }, log.readText())
            assertTrue(native.listFiles().orEmpty().any { it.name.endsWith(".dll") }, native.list().orEmpty().joinToString())
        } finally {
            MediaKeys.unregister()
        }
    }
}
