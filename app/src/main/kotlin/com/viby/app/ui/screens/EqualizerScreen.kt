package com.viby.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.viby.app.state.AppState
import com.viby.app.theme.VibyColors
import com.viby.app.ui.NameDialog
import com.viby.core.playback.EqPresets
import com.viby.core.playback.Equalizer
import com.viby.core.playback.EqualizerController
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** 20-band equalizer screen mirroring the Android EqualizerActivity. */
@Composable
fun EqualizerScreen(state: AppState) {
    val controller = state.engine.equalizer()
    var enabled by remember { mutableStateOf(controller.isEnabled) }
    var preset by remember { mutableStateOf(controller.preset()) }
    var preamp by remember { mutableStateOf(controller.preamp()) }
    val gains = remember { mutableStateListOf(*controller.gains().toTypedArray()) }
    var saving by remember { mutableStateOf(false) }

    fun reload() {
        preset = controller.preset()
        preamp = controller.preamp()
        controller.gains().forEachIndexed { band, gain -> gains[band] = gain }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 32.dp, end = 32.dp, top = 64.dp, bottom = 32.dp),
    ) {
        Column(Modifier.widthIn(max = 980.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Эквалайзер", style = MaterialTheme.typography.headlineMedium, color = VibyColors.TextPrimary,
                    modifier = Modifier.weight(1f))
                Text(if (enabled) "Включён" else "Выключен", color = VibyColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it; controller.setEnabled(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = VibyColors.Accent),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PresetPicker(controller, preset) { name ->
                    controller.applyPreset(name)
                    reload()
                }
                OutlinedButton(onClick = { saving = true }) { Text("Сохранить пресет") }
            }

            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preamp", style = MaterialTheme.typography.titleSmall, color = VibyColors.TextPrimary,
                    modifier = Modifier.width(80.dp))
                Slider(
                    value = preamp,
                    onValueChange = { preamp = it; controller.setPreamp(it, false) },
                    onValueChangeFinished = { controller.commit() },
                    valueRange = -Equalizer.MAX_GAIN_DB..Equalizer.MAX_GAIN_DB,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = VibyColors.Accent,
                        inactiveTrackColor = VibyColors.Highlight,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(db(preamp), color = VibyColors.TextSecondary, style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
            }

            Spacer(Modifier.height(20.dp))
            EqualizerCurve(
                gains = gains,
                active = enabled,
                onGain = { band, gain ->
                    gains[band] = gain
                    controller.setGain(band, gain, false)
                    preset = EqualizerController.PRESET_CUSTOM
                },
                onCommit = { controller.commit() },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Перетаскивайте точки кривой — полоса фиксируется на всё время жеста.",
                style = MaterialTheme.typography.bodySmall,
                color = VibyColors.TextMuted,
            )
        }
    }

    if (saving) {
        NameDialog("Сохранить пресет", preset.takeIf { controller.isCustomPreset(it) } ?: "", "Сохранить",
            { saving = false }) { name ->
            runCatching { controller.saveCurrentAsPreset(name) }
                .onSuccess { preset = controller.preset(); state.showMessage("Пресет «$name» сохранён") }
                .onFailure { state.showMessage(it.message ?: "Не удалось сохранить пресет") }
        }
    }
}

@Composable
private fun PresetPicker(controller: EqualizerController, current: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var names by remember { mutableStateOf(controller.presetNames()) }
    Box {
        Row(
            Modifier
                .width(260.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(VibyColors.Elevated)
                .clickable { names = controller.presetNames(); open = true }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (current == EqualizerController.PRESET_CUSTOM) "Пользовательский" else current,
                color = VibyColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.ExpandMore, null, tint = VibyColors.TextSecondary)
        }
        DropdownMenu(open, { open = false }, containerColor = VibyColors.Highlight) {
            names.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name, color = if (name == current) VibyColors.AccentBright else VibyColors.TextPrimary) },
                    trailingIcon = if (controller.isCustomPreset(name)) {
                        {
                            Icon(Icons.Rounded.Close, "Удалить пресет", tint = VibyColors.TextSecondary,
                                modifier = Modifier.clickable {
                                    controller.deletePreset(name)
                                    names = controller.presetNames()
                                })
                        }
                    } else null,
                    onClick = { onSelect(name); open = false },
                )
            }
        }
    }
}

/**
 * AIMP-style curve: bands are evenly spaced (the grid is already logarithmic), a smooth line
 * runs through the points, and the band under the pointer is locked for the whole drag.
 */
@Composable
private fun EqualizerCurve(
    gains: List<Float>,
    active: Boolean,
    onGain: (Int, Float) -> Unit,
    onCommit: () -> Unit,
) {
    var dragging by remember { mutableStateOf(-1) }
    val lineColor = if (active) VibyColors.Accent else VibyColors.TextMuted
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(VibyColors.Elevated)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row {
            gains.forEachIndexed { band, gain ->
                Text(
                    if (band == dragging || abs(gain) >= 0.05f) String.format(Locale.US, "%+.0f", gain) else "",
                    color = if (band == dragging) VibyColors.AccentBright else VibyColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val step = size.width / Equalizer.BANDS
                        val band = (down.position.x / step).toInt().coerceIn(0, Equalizer.BANDS - 1)
                        dragging = band
                        fun apply(y: Float) = onGain(band, yToGain(y, size.height.toFloat()))
                        apply(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            apply(change.position.y)
                            change.consume()
                        }
                        dragging = -1
                        onCommit()
                    }
                },
        ) {
            val step = size.width / Equalizer.BANDS
            val max = Equalizer.MAX_GAIN_DB
            fun y(gain: Float) = size.height / 2 - gain / max * (size.height / 2 - 10f)
            // grid: 0 dB solid, ±5/±10/±15 dashed
            for (level in listOf(-15f, -10f, -5f, 5f, 10f, 15f)) {
                drawLine(
                    VibyColors.Highlight, Offset(0f, y(level)), Offset(size.width, y(level)),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }
            drawLine(VibyColors.TextMuted, Offset(0f, y(0f)), Offset(size.width, y(0f)))
            for (band in gains.indices) {
                val x = step * band + step / 2
                drawLine(VibyColors.Highlight.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height))
            }
            val points = gains.mapIndexed { band, gain -> Offset(step * band + step / 2, y(gain)) }
            val curve = smoothPath(points)
            val fill = Path().apply {
                addPath(curve)
                lineTo(points.last().x, y(0f))
                lineTo(points.first().x, y(0f))
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.35f), lineColor.copy(alpha = 0.05f))))
            drawPath(curve, lineColor, style = Stroke(width = 3f))
            points.forEachIndexed { band, point ->
                drawCircle(if (band == dragging) Color.White else lineColor, if (band == dragging) 9f else 6f, point)
            }
        }
        Row {
            EqPresets.FREQS_HZ.forEach { freq ->
                Text(
                    frequencyLabel(freq),
                    color = VibyColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Snaps to 0.5 dB steps so values are easy to read and reset. */
private fun yToGain(y: Float, height: Float): Float {
    val max = Equalizer.MAX_GAIN_DB
    val raw = (height / 2 - y) / (height / 2 - 10f) * max
    return ((raw * 2).roundToInt() / 2f).coerceIn(-max, max)
}

/** Catmull-Rom spline through the band points. */
private fun smoothPath(points: List<Offset>): Path = Path().apply {
    moveTo(points.first().x, points.first().y)
    for (i in 0 until points.size - 1) {
        val p0 = points[maxOf(i - 1, 0)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[minOf(i + 2, points.size - 1)]
        cubicTo(
            p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6,
            p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6,
            p2.x, p2.y,
        )
    }
}

private fun frequencyLabel(hz: Float): String =
    if (hz >= 1000) {
        val k = hz / 1000
        if (k == k.toInt().toFloat()) "${k.toInt()}k" else String.format(Locale.US, "%.1fk", k)
    } else {
        hz.toInt().toString()
    }

private fun db(value: Float) = String.format(Locale.US, "%+.1f dB", value)
