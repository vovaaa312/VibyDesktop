package com.viby.app

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.Window
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Windows System Media Transport Controls: Viby shows up in the volume / media flyout with the
 * track, cover and buttons, and Windows routes the keyboard media keys only to the session the
 * user picked there (no global hook, so a browser playing at the same time is left alone).
 *
 * Plain WinRT COM through JNA: interface layouts and enum values were read from
 * Windows.Media.winmd. Every call runs on one MTA thread; button presses arrive on WinRT threads.
 */
class WindowsMediaControls private constructor(
    private val smtc: Pointer,
    private val updater: Pointer,
    private val music: Pointer,
    private val onAction: (MediaKeys.Action) -> Unit,
) {
    private val thread = executor
    private var lastCover: String? = null

    // the delegate lives in native memory and must stay reachable while it is registered
    private val callbacks = listOf<Callback>(
        QueryInterface { self, riid, out ->
            val iid = readGuid(riid)
            if (iid == IID_IUNKNOWN || iid == IID_IAGILE_OBJECT || iid == IID_BUTTON_HANDLER) {
                out.setPointer(0, self)
                S_OK
            } else {
                // diagnostics only: which interface Windows wanted from the button handler
                log("button handler asked for $iid")
                out.setPointer(0, null)
                E_NOINTERFACE
            }
        },
        RefCount { 1 },
        RefCount { 1 },
        Invoke { _, _, args ->
            val button = IntByReference()
            if (com(args, 6, button) >= 0) {
                when (button.value) {
                    BUTTON_PLAY, BUTTON_PAUSE -> onAction(MediaKeys.Action.PLAY_PAUSE)
                    BUTTON_NEXT -> onAction(MediaKeys.Action.NEXT)
                    BUTTON_PREVIOUS -> onAction(MediaKeys.Action.PREVIOUS)
                    BUTTON_STOP -> onAction(MediaKeys.Action.STOP)
                }
            }
            S_OK
        },
    )
    private val vtable = Memory(4L * Native.POINTER_SIZE).apply {
        callbacks.forEachIndexed { i, callback ->
            setPointer(i.toLong() * Native.POINTER_SIZE, com.sun.jna.CallbackReference.getFunctionPointer(callback))
        }
    }
    private val handler = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, vtable) }
    private val token = LongByReference()

    private fun start() = onThread {
        check(com(smtc, 32, handler, token), "add_ButtonPressed")
        // Play/Pause both toggle: the flyout shows whichever matches the playback status
        for (index in listOf(11, 13, 17, 25, 27)) check(com(smtc, index, 1), "enable button $index")
    }

    /** Shows the track in the flyout; [coverUrl] may be a web or file URL. */
    fun update(title: String?, artist: String?, coverUrl: String?, playing: Boolean) = onThread {
        if (title == null) {
            com(smtc, 7, STATUS_STOPPED)
            com(updater, 16)
            com(updater, 7, PLAYBACK_TYPE_MUSIC)
            com(updater, 17)
            lastCover = null
            return@onThread
        }
        com(smtc, 7, if (playing) STATUS_PLAYING else STATUS_PAUSED)
        withHString(title) { com(music, 7, it) }
        withHString(artist.orEmpty()) { com(music, 11, it) }
        if (coverUrl != lastCover) {
            lastCover = coverUrl
            val stream = coverUrl?.let(::streamReference)
            com(updater, 11, stream)
            stream?.let(::release)
        }
        com(updater, 17)
    }

    fun close() = onThread {
        com(smtc, 33, token.value)
        com(smtc, 11, 0)
        release(music)
        release(updater)
        release(smtc)
    }

    /** RandomAccessStreamReference.CreateFromUri(new Uri(url)). */
    private fun streamReference(url: String): Pointer? {
        val uriFactory = factory("Windows.Foundation.Uri", IID_URI_FACTORY) ?: return null
        val statics = factory("Windows.Storage.Streams.RandomAccessStreamReference", IID_STREAM_REFERENCE_STATICS)
            ?: return null.also { release(uriFactory) }
        return try {
            val uri = PointerByReference()
            val created = withHString(url) { com(uriFactory, 6, it, uri) }
            if (created < 0) return null
            val reference = PointerByReference()
            val hr = com(statics, 7, uri.value, reference)
            release(uri.value)
            if (hr >= 0) reference.value else null
        } finally {
            release(uriFactory)
            release(statics)
        }
    }

    private fun onThread(block: () -> Unit) {
        thread.execute { runCatching(block).onFailure { log("update failed: $it") } }
    }

    private fun interface QueryInterface : Callback {
        fun invoke(self: Pointer, riid: Pointer, out: Pointer): Int
    }

    private fun interface RefCount : Callback {
        fun invoke(self: Pointer): Int
    }

    private fun interface Invoke : Callback {
        fun invoke(self: Pointer, sender: Pointer?, args: Pointer): Int
    }

    companion object {
        private const val S_OK = 0
        private const val E_NOINTERFACE = 0x80004002.toInt()
        private const val RO_INIT_MULTITHREADED = 1
        private const val STATUS_STOPPED = 2
        private const val STATUS_PLAYING = 3
        private const val STATUS_PAUSED = 4
        private const val PLAYBACK_TYPE_MUSIC = 1
        private const val BUTTON_PLAY = 0
        private const val BUTTON_PAUSE = 1
        private const val BUTTON_STOP = 2
        private const val BUTTON_NEXT = 6
        private const val BUTTON_PREVIOUS = 7

        private val IID_IUNKNOWN = UUID.fromString("00000000-0000-0000-c000-000000000046")
        private val IID_IAGILE_OBJECT = UUID.fromString("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90")
        private val IID_SMTC_INTEROP = UUID.fromString("ddb0472d-c911-4a1f-86d9-dc3d71a95f5a")
        private val IID_SMTC = UUID.fromString("99fa3ff4-1742-42a6-902e-087d41f965ec")
        private val IID_URI_FACTORY = UUID.fromString("44a9796f-723e-4fdf-a218-033e75b0c084")
        private val IID_STREAM_REFERENCE_STATICS = UUID.fromString("857309dc-3fbf-4e7d-986f-ef3b1a07a964")

        /** TypedEventHandler<SystemMediaTransportControls, …ButtonPressedEventArgs>, a parameterized IID. */
        internal val IID_BUTTON_HANDLER = parameterizedIid(
            "pinterface({9de1c534-6ae1-11e0-84e1-18a905bcc53f};" +
                "rc(Windows.Media.SystemMediaTransportControls;{99fa3ff4-1742-42a6-902e-087d41f965ec});" +
                "rc(Windows.Media.SystemMediaTransportControlsButtonPressedEventArgs;{b7f47116-a56f-4dc8-9e11-92031f4a87c2}))"
        )

        private val executor = Executors.newSingleThreadExecutor { Thread(it, "viby-smtc").apply { isDaemon = true } }
        private var logFile: File? = null

        /**
         * Registers [window] with SMTC; null when unavailable (not Windows, old Windows, errors),
         * in which case the caller falls back to the global keyboard hook.
         */
        fun create(window: Window, log: File?, onAction: (MediaKeys.Action) -> Unit): WindowsMediaControls? {
            if (!System.getProperty("os.name").startsWith("Windows")) return null
            logFile = log
            return runCatching {
                executor.submit<WindowsMediaControls?> {
                    function("RoInitialize").invokeInt(arrayOf(RO_INIT_MULTITHREADED))
                    val interop = factory("Windows.Media.SystemMediaTransportControls", IID_SMTC_INTEROP)
                        ?: return@submit null.also { log("no SMTC activation factory") }
                    val smtc = PointerByReference()
                    // the interop interface derives from IInspectable: GetForWindow is method 6
                    val hr = com(interop, 6, Native.getWindowPointer(window), guidMemory(IID_SMTC), smtc)
                    release(interop)
                    if (hr < 0) return@submit null.also { log("GetForWindow failed: 0x%08X".format(hr)) }
                    val updater = PointerByReference()
                    check(com(smtc.value, 8, updater), "get_DisplayUpdater")
                    // MusicProperties exist only once the content type is Music
                    check(com(updater.value, 7, PLAYBACK_TYPE_MUSIC), "put_Type")
                    val music = PointerByReference()
                    check(com(updater.value, 12, music), "get_MusicProperties")
                    WindowsMediaControls(smtc.value, updater.value, music.value, onAction)
                }.get()?.also {
                    it.start()
                    log("media session registered")
                }
            }.onFailure { log("media session failed: $it") }.getOrNull()
        }

        /** WinRT parameterized interface IID: UUID v5 over the type signature (WinRT spec). */
        internal fun parameterizedIid(signature: String): UUID {
            val namespace = UUID.fromString("11f47ad5-7b73-42c0-abae-878b1e16adee")
            val sha1 = MessageDigest.getInstance("SHA-1")
            sha1.update(ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits)
                .putLong(namespace.leastSignificantBits).array())
            val hash = sha1.digest(signature.toByteArray(Charsets.UTF_8)).copyOf(16)
            hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
            hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
            val buffer = ByteBuffer.wrap(hash)
            return UUID(buffer.long, buffer.long)
        }

        private fun function(name: String) = Function.getFunction("combase", name, Function.ALT_CONVENTION)

        /** Calls method [index] of a COM interface; returns the HRESULT. */
        private fun com(self: Pointer, index: Int, vararg args: Any?): Int {
            val method = self.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE)
            return Function.getFunction(method, Function.ALT_CONVENTION).invokeInt(arrayOf(self, *args))
        }

        private fun release(self: Pointer) {
            com(self, 2)
        }

        private fun check(hr: Int, what: String) {
            if (hr < 0) error("$what failed: 0x%08X".format(hr))
        }

        private fun factory(className: String, iid: UUID): Pointer? {
            val out = PointerByReference()
            val hr = withHString(className) { function("RoGetActivationFactory").invokeInt(arrayOf(it, guidMemory(iid), out)) }
            return if (hr >= 0) out.value else null
        }

        private fun <T> withHString(text: String, block: (Pointer?) -> T): T {
            val handle = PointerByReference()
            function("WindowsCreateString").invokeInt(arrayOf(WString(text), text.length, handle))
            return try {
                block(handle.value)
            } finally {
                handle.value?.let { function("WindowsDeleteString").invokeInt(arrayOf(it)) }
            }
        }

        /** GUID in memory: Data1..3 little-endian, Data4 as bytes. */
        private fun guidMemory(uuid: UUID): Memory = Memory(16).apply {
            val high = uuid.mostSignificantBits
            setInt(0, (high ushr 32).toInt())
            setShort(4, (high ushr 16).toShort())
            setShort(6, high.toShort())
            for (i in 0 until 8) setByte(8L + i, (uuid.leastSignificantBits ushr (56 - 8 * i)).toByte())
        }

        private fun readGuid(pointer: Pointer): UUID {
            val data1 = pointer.getInt(0).toLong() and 0xffffffffL
            val data2 = pointer.getShort(4).toLong() and 0xffffL
            val data3 = pointer.getShort(6).toLong() and 0xffffL
            var low = 0L
            for (i in 0 until 8) low = (low shl 8) or (pointer.getByte(8L + i).toLong() and 0xffL)
            return UUID((data1 shl 32) or (data2 shl 16) or data3, low)
        }

        private fun log(line: String) {
            runCatching { logFile?.appendText("${LocalDateTime.now()} $line\n") }
        }
    }
}
