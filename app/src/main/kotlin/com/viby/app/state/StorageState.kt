package com.viby.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.viby.app.ui.screens.filesWord
import com.viby.app.ui.tracksLabel
import com.viby.core.data.LibraryStorage
import com.viby.core.data.PlaylistExport
import com.viby.core.download.DownloadJob
import com.viby.core.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Path
import java.time.LocalDate
import com.viby.core.data.Track as CoreTrack

/** Stage 3 (data safety): backups, playlist export, relinking files and the storage screen. */
class StorageState(private val app: AppState) {

    var report by mutableStateOf<LibraryStorage.Report?>(null)
        private set

    /** What a long operation is doing ("Ищу файлы… 12 из 300"); null when idle. */
    var busy by mutableStateOf<String?>(null)
        private set

    fun refresh() = run("Считаю размер медиатеки…") { refreshNow() }

    private suspend fun refreshNow() {
        report = withContext(Dispatchers.IO) { app.engine.storage().report() }
    }

    /** Relinks moved files; [adopt] also adds files the library does not know to their folder's playlist. */
    fun scan(adopt: Boolean) = run("Ищу файлы в папке с музыкой…") {
        val result = withContext(Dispatchers.IO) {
            app.engine.scanner().scan(adopt) { done, total -> progress("Читаю теги файлов… $done из $total") }
        }
        refreshNow()
        val parts = buildList {
            if (result.relinked() > 0) add("найдено файлов: ${result.relinked()}")
            if (result.adopted() > 0) add("добавлено в медиатеку: ${result.adopted()}")
        }
        app.showMessage(if (parts.isEmpty()) "Новых файлов не найдено" else parts.joinToString(", ").replaceFirstChar(Char::uppercase))
        offerRedownload(result.missing())
    }

    fun deleteTemp() = run("Удаляю временные файлы…") {
        // paused and running downloads resume from their .part files
        val busyPlaylists = app.engine.downloads().jobs
            .filter { it.status !in FINISHED }
            .mapNotNull { it.playlistName }
        val freed = withContext(Dispatchers.IO) { app.engine.storage().deleteTemp(busyPlaylists) }
        refreshNow()
        app.showMessage("Освобождено ${Formats.bytes(freed)}")
    }

    fun deleteUnreferenced() {
        val files = report?.unreferenced()?.size ?: return
        app.ask(
            "Удалить файлы вне медиатеки?",
            "$files ${filesWord(files)} в папке с музыкой не относятся ни к одному плейлисту. " +
                "Они будут удалены с диска.",
            PromptAction("Удалить") {
                run("Удаляю файлы…") {
                    val freed = withContext(Dispatchers.IO) { app.engine.storage().deleteUnreferenced() }
                    refreshNow()
                    app.showMessage("Освобождено ${Formats.bytes(freed)}")
                }
            },
        )
    }

    // ------------------------------------------------------------------ backups

    fun exportLibrary(withMusic: Boolean) {
        val extension = if (withMusic) "zip" else "json"
        val target = chooseSave("Резервная копия медиатеки", "viby-library-${LocalDate.now()}.$extension") ?: return
        run(if (withMusic) "Упаковываю музыку…" else "Сохраняю медиатеку…") {
            withContext(Dispatchers.IO) {
                app.engine.backup().export(target, null, withMusic) { done, total ->
                    progress("Упаковываю музыку… $done из $total")
                }
            }
            app.showMessage("Резервная копия сохранена: ${target.fileName}")
        }
    }

    fun restoreLibrary(title: String = "Восстановить медиатеку") {
        val source = chooseOpen(title, "*.json;*.zip") ?: return
        run("Восстанавливаю медиатеку…") {
            val result = withContext(Dispatchers.IO) {
                app.engine.backup().importFrom(source) { done, _ -> progress("Распаковываю музыку… $done") }
            }
            if (result.settingsRestored()) app.reloadSettings()
            // backups made on another computer keep only file names: find them in this music folder
            val scan = withContext(Dispatchers.IO) { app.engine.scanner().scan(false) { _, _ -> } }
            refreshNow()
            app.showMessage(buildString {
                append("Восстановлено: ${tracksLabel(result.tracks())}")
                if (result.playlists() > 0) append(", новых плейлистов: ${result.playlists()}")
                if (result.skipped() > 0) append(", уже были: ${result.skipped()}")
            })
            offerRedownload(scan.missing())
        }
    }

    /** Playlist → M3U8 for other players, or the Viby JSON format for another Viby. */
    fun exportPlaylist(name: String, json: Boolean) {
        val target = chooseSave("Экспорт «$name»", "${name.replace(Regex("""[\\/:*?"<>|]"""), "_")}.${if (json) "json" else "m3u8"}")
            ?: return
        run("Экспортирую «$name»…") {
            val written = withContext(Dispatchers.IO) {
                if (json) {
                    app.engine.backup().export(target, listOf(name), false) { _, _ -> }
                    app.engine.library().tracks(name).size
                } else {
                    PlaylistExport.writeM3u(app.engine.library().tracks(name), target)
                }
            }
            app.showMessage("«$name»: ${tracksLabel(written)} → ${target.fileName}")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun offerRedownload(missing: List<CoreTrack>) {
        val restorable = missing.filter { it.videoId != null || !it.sourceUrl.isNullOrEmpty() }
        if (restorable.isEmpty()) return
        app.ask(
            "Скачать недостающие треки?",
            "Файлы ${tracksLabel(restorable.size)} не найдены в папке с музыкой. " +
                "Их можно скачать заново с YouTube — они останутся на своих местах в плейлистах.",
            PromptAction("Скачать ${tracksLabel(restorable.size)}") {
                app.io {
                    val queued = app.engine.redownload(restorable)
                    app.scope.launch { app.showMessage("В очередь загрузок: ${tracksLabel(queued)}") }
                }
            },
        )
    }

    private fun run(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        app.scope.launch {
            try {
                block()
            } catch (e: Exception) {
                app.showError(e)
            } finally {
                busy = null
            }
        }
    }

    private fun progress(text: String) {
        app.scope.launch { if (busy != null) busy = text }
    }

    private fun chooseSave(title: String, name: String): Path? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE).apply {
            directory = app.settings.musicDirectory().toString()
            file = name
            isVisible = true
        }
        val file = dialog.file ?: return null
        return File(dialog.directory, file).toPath()
    }

    private fun chooseOpen(title: String, pattern: String): Path? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
            // Windows ignores the filename filter but takes a wildcard list as the initial name
            file = pattern
            setFilenameFilter { _, name -> name.endsWith(".json", true) || name.endsWith(".zip", true) }
            isVisible = true
        }
        val file = dialog.file ?: return null
        return File(dialog.directory, file).toPath()
    }

    private companion object {
        val FINISHED = setOf(
            DownloadJob.Status.DONE, DownloadJob.Status.COMPLETED_WITH_ERRORS,
            DownloadJob.Status.FAILED, DownloadJob.Status.CANCELED,
        )
    }
}
