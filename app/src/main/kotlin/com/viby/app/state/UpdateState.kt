package com.viby.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.viby.core.platform.Platform
import com.viby.core.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

/** App self-update: daily check on start, manual check, download and install. */
class UpdateState(private val app: AppState) {
    val version: String = AppState::class.java.getResource("/viby-version.txt")?.readText()?.trim() ?: "dev"

    private val updater = AppUpdater(version, Platform.current(), app.settings, app.dirs.root().resolve("updates"))

    var available by mutableStateOf<AppUpdater.Update?>(null)
        private set
    /** Shows the "update now / later" dialog. */
    var prompt by mutableStateOf(false)
    var checking by mutableStateOf(false)
        private set
    /** 0..1 while the installer downloads, null otherwise. */
    var downloadProgress by mutableStateOf<Float?>(null)
        private set

    fun checkOnStart() = check(manual = false)

    fun checkNow() = check(manual = true)

    private fun check(manual: Boolean) {
        if (checking || version == "dev") return
        checking = true
        app.scope.launch {
            try {
                val update = withContext(Dispatchers.IO) { if (manual) updater.check() else updater.checkIfDue() }
                available = update
                if (update != null) prompt = true
                else if (manual) app.showMessage("Установлена последняя версия ($version)")
            } catch (e: Exception) {
                if (manual) app.showError(e, "Не удалось проверить обновления")
            } finally {
                checking = false
            }
        }
    }

    /** Downloads and verifies the installer, starts it and exits so files can be replaced. */
    fun install() {
        val update = available ?: return
        if (downloadProgress != null) return
        prompt = false
        downloadProgress = 0f
        app.scope.launch {
            try {
                val installer = withContext(Dispatchers.IO) {
                    updater.download(update) { done, total ->
                        if (total > 0) app.scope.launch { downloadProgress = done.toFloat() / total }
                    }
                }
                withContext(Dispatchers.IO) { updater.launchInstaller(installer) }
                app.close()
                exitProcess(0)
            } catch (e: Exception) {
                downloadProgress = null
                app.showError(e, "Обновление не установлено")
            }
        }
    }
}
