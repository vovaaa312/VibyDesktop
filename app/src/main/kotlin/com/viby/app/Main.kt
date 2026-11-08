package com.viby.app

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.viby.app.state.AppState
import com.viby.app.state.Screen
import com.viby.app.state.SidePanel
import com.viby.app.theme.VibyColors
import com.viby.app.theme.VibyTheme
import com.viby.app.ui.CoverCache
import com.viby.app.ui.IconAction
import com.viby.app.ui.Panel
import com.viby.app.ui.PlayerBar
import com.viby.app.ui.RightPanel
import com.viby.app.ui.Sidebar
import com.viby.app.ui.screens.AddScreen
import com.viby.app.ui.screens.DownloadsScreen
import com.viby.app.ui.screens.EqualizerScreen
import com.viby.app.ui.screens.HomeScreen
import com.viby.app.ui.screens.PlaylistScreen
import com.viby.app.ui.screens.SettingsScreen
import com.viby.app.ui.screens.StorageScreen
import com.viby.app.ui.screens.SearchScreen
import com.viby.app.ui.screens.YoutubePlaylistsScreen
import com.viby.core.VibyEngine
import com.viby.core.platform.AppDirs
import com.viby.core.tools.Tool
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.awt.Dimension
import kotlin.system.exitProcess
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/** Writes uncaught exceptions to &lt;app dir&gt;/logs/crash.log so crashes can be diagnosed later. */
private val crashLogInstalled: Boolean = run {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching {
            val log = AppDirs.current().logs().resolve("crash.log").toFile()
            val trace = java.io.StringWriter().also { error.printStackTrace(java.io.PrintWriter(it)) }
            log.appendText("=== ${java.time.LocalDateTime.now()} [${thread.name}]\n$trace\n")
        }
        previous?.uncaughtException(thread, error) ?: error.printStackTrace()
    }
    true
}

fun main() = application {
    check(crashLogInstalled)
    val appIcon = remember {
        BitmapPainter(Image.makeFromEncoded(AppState::class.java.getResource("/viby_icon.png")!!.readBytes())
            .toComposeImageBitmap())
    }
    val state = remember {
        AppState().also {
            CoverCache.directory = it.dirs.root().resolve("covers")
            it.start()
            it.updates.checkOnStart()
        }
    }
    val onMediaAction: (MediaKeys.Action) -> Unit = remember {
        { action ->
            state.scope.launch {
                when (action) {
                    MediaKeys.Action.PLAY_PAUSE -> state.player.togglePlay()
                    MediaKeys.Action.NEXT -> state.player.next()
                    MediaKeys.Action.PREVIOUS -> state.player.previous()
                    MediaKeys.Action.STOP -> if (state.player.isPlaying) state.player.togglePlay()
                }
            }
        }
    }
    var mediaControls by remember { mutableStateOf<WindowsMediaControls?>(null) }
    var visible by remember { mutableStateOf(true) }
    Window(
        onCloseRequest = {
            // hide at once; cleanup (hook, downloads, audio, database) must not keep the window frozen
            visible = false
            val cleanup = Thread({
                runCatching { MediaKeys.unregister() }
                runCatching { mediaControls?.close() }
                state.close()
            }, "viby-shutdown").apply { isDaemon = true; start() }
            cleanup.join(3_000)
            // an interrupted download simply resumes on the next start
            exitProcess(0)
        },
        visible = visible,
        title = "Viby",
        icon = appIcon,
        state = rememberWindowState(size = DpSize(1360.dp, 860.dp)),
        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) return@Window false
            when {
                event.key == Key.Spacebar -> state.player.togglePlay()
                event.isCtrlPressed && event.key == Key.DirectionRight -> state.player.next()
                event.isCtrlPressed && event.key == Key.DirectionLeft -> state.player.previous()
                event.isCtrlPressed && event.key == Key.L -> state.toggleSidePanel(SidePanel.LYRICS)
                event.isCtrlPressed && event.key == Key.Q -> state.toggleSidePanel(SidePanel.QUEUE)
                event.isCtrlPressed && event.key == Key.F -> state.navigate(Screen.Search)
                else -> return@Window false
            }
            true
        },
    ) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(1000, 640) }
        // the app background (VibyColors.Background) behind white caption text
        LaunchedEffect(Unit) { WindowsTitleBar.apply(window, caption = 0x000000, text = 0xFFFFFF) }
        LaunchedEffect(Unit) { state.player.runClock() }
        LaunchedEffect(Unit) {
            val log = state.dirs.logs().resolve("media-keys.log").toFile()
            // Windows: a real media session (flyout, keys go only to the session picked there);
            // elsewhere, or if it fails, the global keyboard hook
            val controls = withContext(Dispatchers.IO) { WindowsMediaControls.create(window, log, onMediaAction) }
            if (controls == null) {
                withContext(Dispatchers.IO) {
                    MediaKeys.register(log, state.dirs.root().resolve("native").toFile(), onMediaAction)
                }
                return@LaunchedEffect
            }
            mediaControls = controls
            snapshotFlow { state.player.current to state.player.isPlaying }.collect { (track, playing) ->
                controls.update(track?.title, track?.artist, track?.thumbnailUrl, playing)
            }
        }
        VibyTheme { App(state) }
    }
}

@Composable
fun App(state: AppState) {
    Box(Modifier.fillMaxSize().background(VibyColors.Background)) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            BoxWithConstraints(Modifier.weight(1f)) {
            val narrow = maxWidth < 1150.dp
            val sidebarWidth = if (narrow) 240.dp else 300.dp
            val sideWidth = if (narrow) 300.dp else 340.dp
            val panelOpen = state.sidePanel != SidePanel.NONE
            // too little room for three columns: the side panel floats over the content instead
            val overlay = panelOpen && maxWidth - sidebarWidth - sideWidth - 16.dp < 560.dp
            Row(Modifier.fillMaxSize()) {
                Sidebar(state, width = sidebarWidth)
                Spacer(Modifier.width(8.dp))
                Panel(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            EngineBanner(state)
                            Box(Modifier.weight(1f)) {
                                when (val screen = state.screen) {
                                    Screen.Home -> HomeScreen(state)
                                    Screen.Add -> AddScreen(state)
                                    Screen.Downloads -> DownloadsScreen(state)
                                    Screen.Settings -> SettingsScreen(state)
                                    Screen.Equalizer -> EqualizerScreen(state)
                                    Screen.YoutubePlaylists -> YoutubePlaylistsScreen(state)
                                    Screen.Storage -> StorageScreen(state)
                                    Screen.Search -> SearchScreen(state)
                                    is Screen.PlaylistScreen -> PlaylistScreen(state, screen.id)
                                }
                            }
                        }
                        if (state.canGoBack) {
                            Box(
                                Modifier.padding(12.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                            ) {
                                IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", state::back,
                                    tint = VibyColors.TextPrimary)
                            }
                        }
                    }
                }
                if (panelOpen && !overlay) {
                    Spacer(Modifier.width(8.dp))
                    RightPanel(state, width = sideWidth)
                }
            }
            if (overlay) {
                RightPanel(
                    state,
                    width = sideWidth,
                    modifier = Modifier.align(Alignment.CenterEnd).shadow(16.dp, RoundedCornerShape(8.dp)),
                )
            }
            }
            PlayerBar(state)
        }
        Toast(state, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
        UpdateDialog(state)
        PromptDialog(state)
        ErrorDetailsDialog(state)
    }
}

/**
 * [AppState.ask]: one action is a confirm button (duplicate track), several are a list of
 * choices like Android's setItems dialogs ("В Viby и YouTube" / "Только в Viby").
 */
@Composable
private fun PromptDialog(state: AppState) {
    val prompt = state.prompt ?: return
    val single = prompt.actions.singleOrNull()
    AlertDialog(
        onDismissRequest = { state.answer(null) },
        containerColor = VibyColors.Elevated,
        title = { Text(prompt.title) },
        text = {
            Column {
                prompt.text?.let { Text(it) }
                if (single == null) {
                    prompt.actions.forEach { action ->
                        Text(
                            action.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = VibyColors.TextPrimary,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                                .clickable { state.answer(action) }.padding(vertical = 12.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (single != null) TextButton(onClick = { state.answer(single) }) { Text(single.label) }
        },
        dismissButton = { TextButton(onClick = { state.answer(null) }) { Text("Отмена") } },
    )
}

@Composable
private fun UpdateDialog(state: AppState) {
    val updates = state.updates
    val update = updates.available ?: return
    if (!updates.prompt) return
    AlertDialog(
        onDismissRequest = { updates.prompt = false },
        containerColor = VibyColors.Elevated,
        title = { Text("Доступна новая версия ${update.version()}") },
        text = {
            Text(
                "Сейчас установлена ${updates.version}. Установщик скачается с GitHub, " +
                    "будет проверен по SHA-256 и запустится, а Viby закроется.",
            )
        },
        confirmButton = { TextButton(onClick = updates::install) { Text("Обновить") } },
        dismissButton = { TextButton(onClick = { updates.prompt = false }) { Text("Позже") } },
    )
}

/** First-launch tool installation progress, or the error with a retry button. */
@Composable
private fun EngineBanner(state: AppState) {
    val status = state.engineStatus
    if (status.state() == VibyEngine.State.READY) return
    Column(Modifier.fillMaxWidth().background(VibyColors.Elevated).padding(start = 64.dp, end = 24.dp, top = 12.dp, bottom = 12.dp)) {
        when (status.state()) {
            VibyEngine.State.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Не удалось подготовить инструменты: ${status.error() ?: "неизвестная ошибка"}",
                    color = VibyColors.Danger,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { state.engine.retry() }) { Text("Повторить") }
            }
            else -> {
                val tool = status.tool()
                val total = status.totalBytes()
                val label = when (tool) {
                    null -> "Подготовка…"
                    Tool.YT_DLP -> "yt-dlp"
                    Tool.FFMPEG -> "FFmpeg"
                    Tool.DENO -> "Deno"
                }
                val sizes = if (tool != null && total > 0) {
                    " — ${status.downloadedBytes() shr 20} из ${total shr 20} МБ"
                } else ""
                Text(
                    if (tool == null) "Подготовка к работе…"
                    else "Первый запуск: скачиваю $label$sizes. Это нужно один раз, дальше всё работает офлайн.",
                    color = VibyColors.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (tool != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { status.downloadedBytes().toFloat() / total },
                        color = VibyColors.Accent,
                        trackColor = VibyColors.Highlight,
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        color = VibyColors.Accent,
                        trackColor = VibyColors.Highlight,
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Snackbar: at most three lines, closable, disappears after a few seconds unless hovered.
 * Errors keep their raw output behind "Подробнее" instead of filling the window.
 */
@Composable
private fun Toast(state: AppState, modifier: Modifier) {
    val notice = state.message ?: return
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(notice, hovered) {
        if (hovered) return@LaunchedEffect
        delay(if (notice.details != null) 8_000L else (2_500L + notice.text.length * 40L).coerceAtMost(7_000L))
        state.consumeMessage()
    }
    Row(
        modifier
            .widthIn(max = 640.dp)
            .padding(horizontal = 16.dp)
            .shadow(8.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(VibyColors.TextPrimary)
            .hoverable(interaction)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            notice.text,
            color = Color.Black,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(vertical = 4.dp),
        )
        notice.details?.let { details ->
            TextButton(onClick = {
                state.errorDetails = details
                state.consumeMessage()
            }) { Text("Подробнее", color = VibyColors.Accent) }
        }
        IconAction(Icons.Rounded.Close, "Закрыть", state::consumeMessage, tint = Color.Black.copy(alpha = 0.6f))
    }
}

/** Full error output from a toast: selectable, scrollable and copyable for a bug report. */
@Composable
private fun ErrorDetailsDialog(state: AppState) {
    val details = state.errorDetails ?: return
    AlertDialog(
        onDismissRequest = { state.errorDetails = null },
        containerColor = VibyColors.Elevated,
        title = { Text("Подробности ошибки") },
        text = {
            SelectionContainer {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = VibyColors.TextSecondary,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = { TextButton(onClick = { state.errorDetails = null }) { Text("Закрыть") } },
        dismissButton = {
            TextButton(onClick = {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(details), null)
                state.errorDetails = null
                state.showMessage("Скопировано")
            }) { Text("Копировать") }
        },
    )
}
