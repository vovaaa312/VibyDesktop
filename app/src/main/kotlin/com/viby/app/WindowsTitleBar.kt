package com.viby.app

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.Window

/**
 * Dark Windows title bar so the frame matches the app instead of the light system caption.
 * Keeps the native frame (resize edges, snap, caption buttons), unlike an undecorated window.
 * Windows 10 2004+ gets the dark caption; Windows 11 also takes the exact app colours.
 */
object WindowsTitleBar {

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_BORDER_COLOR = 34
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36
    private const val SWP_FRAME_CHANGED = 0x0001 or 0x0002 or 0x0004 or 0x0010 or 0x0020

    /**
     * @param caption and [text] as 0xRRGGBB (Windows 11 only)
     * @return whether the dark caption was accepted
     */
    fun apply(window: Window, caption: Int, text: Int): Boolean {
        if (!System.getProperty("os.name").startsWith("Windows")) return false
        return runCatching {
            val hwnd = Native.getWindowPointer(window)
            val dark = set(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, 1) ||
                set(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1, 1)
            // refused before Windows 11; the dark caption above is the fallback look
            set(hwnd, DWMWA_CAPTION_COLOR, colorRef(caption))
            set(hwnd, DWMWA_BORDER_COLOR, colorRef(caption))
            set(hwnd, DWMWA_TEXT_COLOR, colorRef(text))
            // Windows 10 repaints the caption only after a frame change
            Function.getFunction("user32", "SetWindowPos", Function.ALT_CONVENTION)
                .invokeInt(arrayOf(hwnd, null, 0, 0, 0, 0, SWP_FRAME_CHANGED))
            dark
        }.getOrDefault(false)
    }

    private fun set(hwnd: Pointer, attribute: Int, value: Int): Boolean =
        Function.getFunction("dwmapi", "DwmSetWindowAttribute", Function.ALT_CONVENTION)
            .invokeInt(arrayOf(hwnd, attribute, IntByReference(value), 4)) == 0

    /** COLORREF is 0x00BBGGRR. */
    private fun colorRef(rgb: Int): Int =
        ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)
}
