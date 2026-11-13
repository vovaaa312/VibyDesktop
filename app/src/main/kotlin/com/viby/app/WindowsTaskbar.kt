package com.viby.app

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.Window
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Viby button on the Windows taskbar, like AIMP: hovering it shows the cover of the playing
 * track instead of a shrunken window, with previous / play-pause / next buttons under it
 * (ITaskbarList3 thumbnail toolbar + DWM iconic thumbnail).
 *
 * The window procedure is chained to catch the button clicks (WM_COMMAND), the thumbnail
 * requests and the re-creation of the taskbar button after the window comes back from the tray.
 */
class WindowsTaskbar private constructor(
    private val hwnd: Pointer,
    private val onAction: (MediaKeys.Action) -> Unit,
    private val log: File?,
) {
    private val com = Executors.newSingleThreadExecutor { Thread(it, "viby-taskbar").apply { isDaemon = true } }
    private var taskbar: Pointer? = null
    private val buttonCreated = user32("RegisterWindowMessageW").invokeInt(arrayOf(WString("TaskbarButtonCreated")))
    private var oldProc: Pointer? = null

    @Volatile private var playing = false
    @Volatile private var hasTrack = false
    /** Square cover of the playing track; null shows the Viby icon. */
    @Volatile private var cover: BufferedImage? = null
    private var coverSource: BufferedImage? = null
    private val appIcon: BufferedImage? = runCatching {
        javax.imageio.ImageIO.read(WindowsTaskbar::class.java.getResource("/viby_icon.png"))
    }.getOrNull()

    private val icons by lazy {
        val size = user32("GetSystemMetrics").invokeInt(arrayOf(SM_CXSMICON)).takeIf { it > 0 } ?: 16
        mapOf(
            ICON_PREVIOUS to hicon(drawIcon(size) { s -> skip(s, forward = false) }),
            ICON_PLAY to hicon(drawIcon(size) { s -> play(s) }),
            ICON_PAUSE to hicon(drawIcon(size) { s -> pause(s) }),
            ICON_NEXT to hicon(drawIcon(size) { s -> skip(s, forward = true) }),
        )
    }

    // must stay reachable while it is the window procedure
    private val proc = WndProc { window, msg, wParam, lParam ->
        try {
            when {
                msg == WM_COMMAND && (wParam ushr 16 and 0xFFFF).toInt() == THBN_CLICKED -> {
                    when ((wParam and 0xFFFF).toInt()) {
                        BUTTON_PREVIOUS -> onAction(MediaKeys.Action.PREVIOUS)
                        BUTTON_PLAY_PAUSE -> onAction(MediaKeys.Action.PLAY_PAUSE)
                        BUTTON_NEXT -> onAction(MediaKeys.Action.NEXT)
                    }
                    return@WndProc 0L
                }
                msg == WM_DWMSENDICONICTHUMBNAIL -> {
                    sendThumbnail((lParam ushr 16 and 0xFFFF).toInt(), (lParam and 0xFFFF).toInt())
                    return@WndProc 0L
                }
                msg == buttonCreated -> com.execute { addButtons() }
            }
        } catch (e: Throwable) {
            log("window message failed: $e")
        }
        user32("CallWindowProcW").invokeLong(arrayOf(oldProc, window, msg, wParam, lParam))
    }

    private fun start() {
        oldProc = user32("SetWindowLongPtrW").invokePointer(arrayOf(hwnd, GWLP_WNDPROC, CallbackReference.getFunctionPointer(proc)))
        // the cover replaces the shrunken window; hovering the thumbnail does not "peek" at the window
        dwmSet(DWMWA_FORCE_ICONIC_REPRESENTATION, 1)
        dwmSet(DWMWA_HAS_ICONIC_BITMAP, 1)
        dwmSet(DWMWA_DISALLOW_PEEK, 1)
        com.execute {
            ole32("CoInitializeEx").invokeInt(arrayOf(null, COINIT_APARTMENTTHREADED))
            val out = PointerByReference()
            val hr = ole32("CoCreateInstance").invokeInt(
                arrayOf(guid(CLSID_TASKBAR_LIST), null, CLSCTX_INPROC_SERVER, guid(IID_TASKBAR_LIST3), out))
            if (hr < 0) return@execute log("TaskbarList failed: 0x%08X".format(hr))
            taskbar = out.value
            call(3) // HrInit
            addButtons()
        }
    }

    /** Shows the state of the player on the taskbar. */
    fun update(playing: Boolean, hasTrack: Boolean, cover: BufferedImage?) {
        val coverChanged = cover !== coverSource
        coverSource = cover
        this.playing = playing
        this.hasTrack = hasTrack
        if (coverChanged) this.cover = cover?.let(::square)
        com.execute { setButtons(update = true) }
        if (coverChanged) dwm("DwmInvalidateIconicBitmaps").invokeInt(arrayOf(hwnd))
    }

    private fun addButtons() = setButtons(update = false)

    private fun setButtons(update: Boolean) {
        taskbar ?: return
        val buttons = Memory(3L * THUMBBUTTON_SIZE).apply { clear() }
        val flags = if (hasTrack) THBF_ENABLED else THBF_DISABLED
        listOf(
            Triple(BUTTON_PREVIOUS, ICON_PREVIOUS, "Предыдущий трек"),
            Triple(BUTTON_PLAY_PAUSE, if (playing) ICON_PAUSE else ICON_PLAY, if (playing) "Пауза" else "Играть"),
            Triple(BUTTON_NEXT, ICON_NEXT, "Следующий трек"),
        ).forEachIndexed { i, (id, icon, tip) ->
            val at = i.toLong() * THUMBBUTTON_SIZE
            buttons.setInt(at, THB_ICON or THB_TOOLTIP or THB_FLAGS)
            buttons.setInt(at + 4, id)
            buttons.setPointer(at + 16, icons[icon])
            buttons.setWideString(at + 24, tip)
            buttons.setInt(at + 544, flags)
        }
        val hr = call(if (update) 16 else 15, hwnd, 3, buttons)
        if (hr < 0 && !update) log("ThumbBarAddButtons failed: 0x%08X".format(hr))
    }

    /** WM_DWMSENDICONICTHUMBNAIL: the cover (or the icon), fitted into the size the taskbar asks for. */
    private fun sendThumbnail(maxWidth: Int, maxHeight: Int) {
        val image = cover ?: appIcon ?: return
        val side = min(maxWidth, maxHeight).coerceAtLeast(1)
        val frame = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB_PRE)
        frame.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            if (cover == null) {
                // the icon on the app background, a bit smaller than the frame
                color = Color(0x12, 0x12, 0x12)
                fillRect(0, 0, side, side)
                val inset = side / 5
                drawImage(image, inset, inset, side - 2 * inset, side - 2 * inset, null)
            } else {
                drawImage(image, 0, 0, side, side, null)
            }
            dispose()
        }
        val bitmap = dibSection(frame) ?: return
        try {
            dwm("DwmSetIconicThumbnail").invokeInt(arrayOf(hwnd, bitmap, 0))
        } finally {
            gdi32("DeleteObject").invokeInt(arrayOf(bitmap))
        }
    }

    fun close() {
        runCatching { oldProc?.let { user32("SetWindowLongPtrW").invokePointer(arrayOf(hwnd, GWLP_WNDPROC, it)) } }
        com.shutdownNow()
    }

    private fun call(index: Int, vararg args: Any?): Int {
        val self = taskbar ?: return -1
        val method = self.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE)
        return Function.getFunction(method, Function.ALT_CONVENTION).invokeInt(arrayOf(self, *args))
    }

    private fun dwmSet(attribute: Int, value: Int) {
        dwm("DwmSetWindowAttribute").invokeInt(arrayOf(hwnd, attribute, IntByReference(value), 4))
    }

    private fun log(line: String) {
        runCatching { log?.appendText("${LocalDateTime.now()} taskbar: $line\n") }
    }

    private fun interface WndProc : Callback {
        fun invoke(hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long
    }

    companion object {
        private const val GWLP_WNDPROC = -4
        private const val WM_COMMAND = 0x0111
        private const val WM_DWMSENDICONICTHUMBNAIL = 0x0323
        private const val THBN_CLICKED = 0x1800
        private const val DWMWA_FORCE_ICONIC_REPRESENTATION = 7
        private const val DWMWA_HAS_ICONIC_BITMAP = 10
        private const val DWMWA_DISALLOW_PEEK = 11
        private const val COINIT_APARTMENTTHREADED = 2
        private const val CLSCTX_INPROC_SERVER = 1
        private const val SM_CXSMICON = 49
        private const val THB_ICON = 0x2
        private const val THB_TOOLTIP = 0x4
        private const val THB_FLAGS = 0x8
        private const val THBF_ENABLED = 0
        private const val THBF_DISABLED = 1
        /** THUMBBUTTON on x64: mask, id, bitmap, (pad), HICON, WCHAR[260], flags, (pad). */
        private const val THUMBBUTTON_SIZE = 552
        private const val BUTTON_PREVIOUS = 1
        private const val BUTTON_PLAY_PAUSE = 2
        private const val BUTTON_NEXT = 3
        private const val ICON_PREVIOUS = "previous"
        private const val ICON_PLAY = "play"
        private const val ICON_PAUSE = "pause"
        private const val ICON_NEXT = "next"
        private val CLSID_TASKBAR_LIST = UUID.fromString("56fdf344-fd6d-11d0-958a-006097c9a090")
        private val IID_TASKBAR_LIST3 = UUID.fromString("ea1afb91-9e28-4b86-90e9-9e9f8a5eefaf")

        /** Null outside 64-bit Windows or when the window has no native handle. */
        fun create(window: Window, log: File?, onAction: (MediaKeys.Action) -> Unit): WindowsTaskbar? {
            if (!System.getProperty("os.name").startsWith("Windows") || Native.POINTER_SIZE != 8) return null
            return runCatching {
                val hwnd = Native.getWindowPointer(window) ?: return null
                WindowsTaskbar(hwnd, onAction, log).also { it.start() }
            }.onFailure { e -> runCatching { log?.appendText("${LocalDateTime.now()} taskbar failed: $e\n") } }
                .getOrNull()
        }

        private fun user32(name: String) = Function.getFunction("user32", name, Function.ALT_CONVENTION)
        private fun gdi32(name: String) = Function.getFunction("gdi32", name, Function.ALT_CONVENTION)
        private fun ole32(name: String) = Function.getFunction("ole32", name, Function.ALT_CONVENTION)
        private fun dwm(name: String) = Function.getFunction("dwmapi", name, Function.ALT_CONVENTION)

        /** Centre square of a cover: YouTube thumbnails are 16:9. */
        private fun square(image: BufferedImage): BufferedImage {
            val side = min(image.width, image.height)
            return image.getSubimage((image.width - side) / 2, (image.height - side) / 2, side, side)
        }

        /** Top-down 32-bit DIB section with the pixels of [image] (BGRA in memory). */
        private fun dibSection(image: BufferedImage): Pointer? {
            val width = image.width
            val height = image.height
            val header = Memory(40).apply {
                clear()
                setInt(0, 40)
                setInt(4, width)
                setInt(8, -height) // negative: rows from the top
                setShort(12, 1)
                setShort(14, 32)
            }
            val bits = PointerByReference()
            val bitmap = gdi32("CreateDIBSection").invokePointer(arrayOf(null, header, 0, bits, null, 0)) ?: return null
            val pixels = image.getRGB(0, 0, width, height, null, 0, width)
            if (image.type == BufferedImage.TYPE_INT_ARGB_PRE) {
                // getRGB un-premultiplies; DWM wants premultiplied alpha
                for (i in pixels.indices) pixels[i] = premultiply(pixels[i])
            }
            bits.value.write(0, pixels, 0, pixels.size)
            return bitmap
        }

        private fun premultiply(argb: Int): Int {
            val a = argb ushr 24
            if (a == 255) return argb
            val r = (argb shr 16 and 0xFF) * a / 255
            val g = (argb shr 8 and 0xFF) * a / 255
            val b = (argb and 0xFF) * a / 255
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        /** HICON from a straight-alpha image. */
        private fun hicon(image: BufferedImage): Pointer? {
            val color = dibSection(image) ?: return null
            val mask = gdi32("CreateBitmap").invokePointer(arrayOf(image.width, image.height, 1, 1, null))
            val info = Memory(32).apply {
                clear()
                setInt(0, 1) // fIcon
                setPointer(16, mask)
                setPointer(24, color)
            }
            return user32("CreateIconIndirect").invokePointer(arrayOf(info)).also {
                gdi32("DeleteObject").invokeInt(arrayOf(color))
                gdi32("DeleteObject").invokeInt(arrayOf(mask))
            }
        }

        private fun drawIcon(size: Int, shape: (Float) -> java.awt.Shape): BufferedImage =
            BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
                createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                    color = Color.WHITE
                    stroke = BasicStroke(0f)
                    fill(shape(size.toFloat()))
                    dispose()
                }
            }

        private fun play(s: Float) = Path2D.Float().apply {
            moveTo(s * 0.28f, s * 0.16f)
            lineTo(s * 0.84f, s * 0.5f)
            lineTo(s * 0.28f, s * 0.84f)
            closePath()
        }

        private fun pause(s: Float) = Path2D.Float().apply {
            val w = (s * 0.2f).roundToInt().toFloat()
            append(java.awt.geom.Rectangle2D.Float(s * 0.25f, s * 0.18f, w, s * 0.64f), false)
            append(java.awt.geom.Rectangle2D.Float(s * 0.75f - w, s * 0.18f, w, s * 0.64f), false)
        }

        private fun skip(s: Float, forward: Boolean) = Path2D.Float().apply {
            val bar = s * 0.14f
            if (forward) {
                moveTo(s * 0.18f, s * 0.18f)
                lineTo(s * 0.68f, s * 0.5f)
                lineTo(s * 0.18f, s * 0.82f)
                closePath()
                append(java.awt.geom.Rectangle2D.Float(s * 0.68f, s * 0.18f, bar, s * 0.64f), false)
            } else {
                moveTo(s * 0.82f, s * 0.18f)
                lineTo(s * 0.32f, s * 0.5f)
                lineTo(s * 0.82f, s * 0.82f)
                closePath()
                append(java.awt.geom.Rectangle2D.Float(s * 0.32f - bar, s * 0.18f, bar, s * 0.64f), false)
            }
        }

        private fun guid(uuid: UUID): Memory = Memory(16).apply {
            val high = uuid.mostSignificantBits
            setInt(0, (high ushr 32).toInt())
            setShort(4, (high ushr 16).toShort())
            setShort(6, high.toShort())
            for (i in 0 until 8) setByte(8L + i, (uuid.leastSignificantBits ushr (56 - 8 * i)).toByte())
        }
    }
}
