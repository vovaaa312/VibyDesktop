package com.viby.app

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import java.io.File
import java.time.LocalDateTime
import java.util.logging.Level
import java.util.logging.Logger

/**
 * System-wide media keys (keyboard Play/Pause, Next, Previous, Stop, including Fn combos) via a
 * global keyboard hook, so they work while Viby is in the background. Callbacks run on the hook
 * thread; the caller hops to the UI thread.
 */
object MediaKeys {

    enum class Action { PLAY_PAUSE, NEXT, PREVIOUS, STOP }

    private var listener: NativeKeyListener? = null
    private var log: File? = null

    /** Windows virtual-key codes, in case the hook reports a media key without mapping it. */
    private val windowsVirtualKeys = mapOf(
        0xB3 to Action.PLAY_PAUSE, 0xB0 to Action.NEXT, 0xB1 to Action.PREVIOUS, 0xB2 to Action.STOP,
    )

    private val mediaKeyCodes = mapOf(
        NativeKeyEvent.VC_MEDIA_PLAY to Action.PLAY_PAUSE,
        NativeKeyEvent.VC_MEDIA_NEXT to Action.NEXT,
        NativeKeyEvent.VC_MEDIA_PREVIOUS to Action.PREVIOUS,
        NativeKeyEvent.VC_MEDIA_STOP to Action.STOP,
    )

    /**
     * @param logFile receives hook status and media/volume keys only (never other keys)
     * @return false when the OS refused the hook (e.g. Wayland, missing accessibility rights)
     */
    fun register(logFile: File?, onAction: (Action) -> Unit): Boolean {
        if (listener != null) return true
        log = logFile
        // the hook logs every event at INFO by default
        Logger.getLogger(GlobalScreen::class.java.`package`.name).apply {
            level = Level.WARNING
            useParentHandlers = false
        }
        return try {
            GlobalScreen.registerNativeHook()
            val keys = object : NativeKeyListener {
                override fun nativeKeyPressed(event: NativeKeyEvent) {
                    val action = mediaKeyCodes[event.keyCode] ?: windowsVirtualKeys[event.rawCode]
                    if (action != null || isMediaOrVolume(event)) {
                        write("key keyCode=0x%X rawCode=0x%X -> %s".format(event.keyCode, event.rawCode, action))
                    }
                    action?.let(onAction)
                }
            }
            GlobalScreen.addNativeKeyListener(keys)
            listener = keys
            write("hook registered")
            true
        } catch (e: NativeHookException) {
            write("hook failed: $e")
            false
        } catch (e: UnsatisfiedLinkError) {
            write("hook failed: $e")
            false
        }
    }

    fun unregister() {
        listener?.let(GlobalScreen::removeNativeKeyListener)
        listener = null
        runCatching { GlobalScreen.unregisterNativeHook() }
    }

    /** VC_MEDIA_* / VC_VOLUME_* live in 0xE000..0xE0FF; plain typing keys are never logged. */
    private fun isMediaOrVolume(event: NativeKeyEvent) = event.keyCode in 0xE000..0xE0FF

    private fun write(line: String) {
        runCatching { log?.appendText("${LocalDateTime.now()} $line\n") }
    }
}
