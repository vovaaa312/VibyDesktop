package com.viby.app.state

import com.viby.core.download.DownloadFailure
import com.viby.core.download.DownloadService

/**
 * Raw yt-dlp / FFmpeg / exception text → one short sentence for the toast.
 * The raw text is kept by the caller and shown only under "Подробнее".
 */
object ErrorText {

    private const val MAX_LENGTH = 160
    private val URL = Regex("""https?://\S+""")
    /** "[in#0 @ 0000024…]", "ERROR:", "WARNING:" prefixes of tool output. */
    private val PREFIX = Regex("""^(\[[^\]]*]\s*|(ERROR|WARNING):\s*)+""")

    fun humanize(raw: String?): String {
        // stream URLs are long random tokens that can accidentally match the keywords below
        val text = raw.orEmpty().replace(URL, "").trim()
        if (text.isEmpty()) return "Неизвестная ошибка"
        val lower = text.lowercase()
        if ("403" in lower && "forbidden" in lower) return "YouTube отклонил запрос (403) — попробуйте ещё раз"
        val code = DownloadFailure.classify(RuntimeException(text)).code
        if (code != DownloadFailure.Code.UNKNOWN) return DownloadService.failureMessage(code)
        return shorten(text)
    }

    /** First meaningful line without tool prefixes, cut to a toast-sized length. */
    internal fun shorten(text: String): String {
        val line = text.lineSequence().map { it.replace(PREFIX, "").trim() }.firstOrNull { it.isNotEmpty() }
            ?: return "Неизвестная ошибка"
        return if (line.length <= MAX_LENGTH) line else line.take(MAX_LENGTH - 1).trimEnd() + "…"
    }
}
