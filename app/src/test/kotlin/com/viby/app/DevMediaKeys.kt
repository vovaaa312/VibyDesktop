package com.viby.app

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener

/** Prints what the global hook receives while Windows injects Mute twice (net effect: none). */
fun main(args: Array<String>) {
    GlobalScreen.registerNativeHook()
    GlobalScreen.addNativeKeyListener(object : NativeKeyListener {
        override fun nativeKeyPressed(e: NativeKeyEvent) =
            println("pressed keyCode=0x%X rawCode=0x%X text=%s".format(e.keyCode, e.rawCode, NativeKeyEvent.getKeyText(e.keyCode)))
    })
    Thread.sleep(1000)
    ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", args[0]).inheritIO().start().waitFor()
    Thread.sleep(1000)
    println("constants: PLAY=0x%X NEXT=0x%X PREV=0x%X MUTE=0x%X".format(
        NativeKeyEvent.VC_MEDIA_PLAY, NativeKeyEvent.VC_MEDIA_NEXT, NativeKeyEvent.VC_MEDIA_PREVIOUS, NativeKeyEvent.VC_VOLUME_MUTE))
    GlobalScreen.unregisterNativeHook()
    kotlin.system.exitProcess(0)
}
