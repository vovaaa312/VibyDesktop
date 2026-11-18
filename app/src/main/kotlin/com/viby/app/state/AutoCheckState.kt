package com.viby.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.viby.core.download.PlaylistWatcher
import com.viby.core.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings → «Новые треки»: checks the playlists downloaded from YouTube for new videos at start
 * and/or every few hours and queues them, so nobody has to press "Обновить из YouTube" by hand.
 */
class AutoCheckState(private val app: AppState) {
    private val settings get() = app.settings
    private val watcher get() = app.engine.watcher()

    /** Settings.AUTO_CHECK_OFF / _START / _ALWAYS. */
    var mode by mutableStateOf(settings.autoCheckMode())
        private set
    var excluded by mutableStateOf(settings.autoCheckExcluded().toSet())
        private set
    var running by mutableStateOf(false)
        private set
    /** "Последняя проверка: …" under the setting. */
    var status by mutableStateOf<String?>(null)
        private set

    /** Shows a notification while Viby is hidden in the tray; set by the window. */
    var notifyHidden: ((String, String) -> Unit)? = null

    private var checkedThisSession = false
    /** When the last automatic round ran (this session); throttles the window-focus trigger. */
    private var lastRound = 0L

    fun changeMode(value: String) {
        mode = value
        settings.setAutoCheckMode(value)
    }

    fun isExcluded(playlist: String) = playlist in excluded

    fun setExcluded(playlist: String, exclude: Boolean) {
        excluded = if (exclude) excluded + playlist else excluded - playlist
        settings.setAutoCheckExcluded(excluded)
        app.showMessage(
            if (exclude) "«$playlist» не будет проверяться автоматически"
            else "«$playlist» снова проверяется автоматически"
        )
    }

    /** Runs for the whole session: the first check after start, then every few minutes ("Постоянно"). */
    fun start() {
        app.scope.launch {
            status = describe(withContext(Dispatchers.IO) { watcher.lastCheck() }, null)
            while (true) {
                if (app.engine.isReady && app.library.loaded && !running) {
                    when {
                        mode == Settings.AUTO_CHECK_OFF -> {}
                        !checkedThisSession -> check(manual = false, policy = AT_START)
                        mode == Settings.AUTO_CHECK_ALWAYS &&
                            System.currentTimeMillis() - lastRound >= ROUND_EVERY_MS -> check(manual = false, policy = ALWAYS)
                    }
                }
                delay(30_000)
            }
        }
    }

    /**
     * The Viby window came to the front: a track added on YouTube a minute ago shows up now, not
     * in ten minutes. At most every two minutes; playlists whose size did not change cost nothing.
     */
    fun onWindowActivated() {
        if (mode != Settings.AUTO_CHECK_ALWAYS || !checkedThisSession || running) return
        if (System.currentTimeMillis() - lastRound < FOCUS_EVERY_MS) return
        check(manual = false, policy = ALWAYS)
    }

    /** "Проверить сейчас" and the timers. */
    fun check(manual: Boolean = true, policy: PlaylistWatcher.Policy = PlaylistWatcher.Policy.MANUAL) {
        if (running) return
        if (!app.engine.isReady) {
            if (manual) app.showMessage("Подождите: идёт подготовка yt-dlp и FFmpeg")
            return
        }
        running = true
        checkedThisSession = true
        lastRound = System.currentTimeMillis()
        if (manual) app.showMessage("Проверяю плейлисты YouTube на новые треки…")
        app.scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { watcher.check(excluded, policy) }
                status = describe(System.currentTimeMillis(), result)
                report(result, manual)
                if (result.quotaExceeded) app.showMessage("Дневной лимит YouTube API исчерпан — приватные плейлисты проверятся завтра")
            } catch (e: Exception) {
                if (manual) app.showError(e, "Не удалось проверить плейлисты")
            } finally {
                running = false
            }
        }
    }

    private fun report(result: PlaylistWatcher.Result, manual: Boolean) {
        if (result.found.isNotEmpty()) {
            val text = result.found.joinToString(", ") { "«${it.playlist}» +${it.count}" }
            app.showMessage("Новые треки: $text — добавлены в загрузки")
            notifyHidden?.invoke("Новые треки: ${result.newTracks()}", "$text — скачиваются")
        } else if (manual) {
            app.showMessage(
                when {
                    result.checked == 0 && result.failed == 0 -> "Нет плейлистов с YouTube для проверки"
                    result.failed > 0 -> "Новых треков нет · не удалось проверить: ${result.failed} (${result.firstError})"
                    else -> "Новых треков нет"
                }
            )
        }
    }

    private fun describe(at: Long, result: PlaylistWatcher.Result?): String? {
        if (at <= 0) return null
        val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        val day = when (time.toLocalDate()) {
            LocalDate.now() -> "сегодня"
            LocalDate.now().minusDays(1) -> "вчера"
            else -> time.format(DateTimeFormatter.ofPattern("d.MM"))
        }
        val base = "Последняя проверка: $day в ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        return when {
            result == null -> base
            result.newTracks() > 0 -> "$base · новых треков: ${result.newTracks()}"
            result.failed > 0 -> "$base · не удалось проверить: ${result.failed}"
            else -> "$base · новых нет"
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        /** "Постоянно": the cheap round (playlist sizes through the account). */
        const val ROUND_EVERY_MS = 10 * MINUTE
        const val FOCUS_EVERY_MS = 2 * MINUTE

        /** At start: every changed playlist; without an account yt-dlp re-reads after 10 minutes. */
        val AT_START = PlaylistWatcher.Policy(false, 10 * MINUTE, 24 * 60 * MINUTE)

        /**
         * Rounds every few minutes: without an account a playlist is read by yt-dlp at most every
         * 30 minutes (YouTube must not take Viby for a bot); with one, only when its size changed.
         */
        val ALWAYS = PlaylistWatcher.Policy(false, 30 * MINUTE, 24 * 60 * MINUTE)
    }
}
