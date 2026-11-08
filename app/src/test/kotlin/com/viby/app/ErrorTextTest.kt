package com.viby.app

import com.viby.app.state.ErrorText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ErrorTextTest {

    /** The ffmpeg output from an expired googlevideo stream URL. */
    @Test
    fun forbiddenStreamBecomesOneSentence() {
        val raw = """
            [in#0 @ 00000246939cfb40] Error opening input: Server returned 403 Forbidden (access denied)
            Error opening input file https://rr2---sn-jxnoxu-2gbl.googlevideo.com/videoplayback?expire=1793806357&ei=tfvqatm5H&sig=AE0s2JYwRQIhAKaHdFhTzqLl0SfRp6S3RXyL5M8FB9hQJ
            Error opening input files: Server returned 403 Forbidden (access denied)
        """.trimIndent()
        assertEquals("YouTube отклонил запрос (403) — попробуйте ещё раз", ErrorText.humanize(raw))
    }

    @Test
    fun knownYtDlpFailuresUseDownloadTexts() {
        assertEquals("Видео приватное или доступно только участникам",
            ErrorText.humanize("ERROR: [youtube] abc: Private video. Sign in if you've been granted access"))
        assertEquals("Соединение с сетью было прервано", ErrorText.humanize("java.net.SocketTimeoutException: Read timed out"))
    }

    @Test
    fun unknownTextKeepsTheFirstLineWithoutToolPrefixes() {
        assertEquals("Something odd happened", ErrorText.humanize("[in#0 @ 0000] Something odd happened\nsecond line"))
        assertEquals("Неизвестная ошибка", ErrorText.humanize("  "))
    }

    @Test
    fun longTextIsCut() {
        val text = ErrorText.humanize("x".repeat(500))
        assertTrue(text.length <= 160 && text.endsWith("…"), text)
    }
}
