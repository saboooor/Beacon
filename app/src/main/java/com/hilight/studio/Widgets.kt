package com.hilight.studio

import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

val PRESET_COLORS = listOf(
    0xFFFF1744, 0xFFFF6D00, 0xFFFFD600, 0xFF00E676, 0xFF00E5FF,
    0xFF2979FF, 0xFF7C4DFF, 0xFFFF4081, 0xFFFFFFFF, 0xFFFF80AB,
).map { it.toInt() }

internal class PreviewLauncher(
    private val launch: (Pattern, Int, Int, Float, Int, Ambient?) -> Unit,
) {
    operator fun invoke(
        pattern: Pattern, color: Int, speedMs: Int, brightness: Float, durationMs: Int,
        look: Ambient? = null,
    ) = launch(pattern, color, speedMs, brightness, durationMs, look)
}

/** Launches every in-app preview through one truthful guard check without adding UI controls. */
@Composable
internal fun rememberPreviewLauncher(store: Store): PreviewLauncher {
    val context = LocalContext.current.applicationContext
    val resources = LocalResources.current
    return remember(store, context, resources) {
        PreviewLauncher { pattern, color, speedMs, brightness, durationMs, look ->
            val reason = store.previewSuppressionReason()
            if (reason == null) {
                if (look != null) store.previewLook(look, durationMs)
                else store.preview(pattern, color, speedMs, brightness, durationMs)
            } else {
                Toast.makeText(
                    context,
                    resources.getString(
                        R.string.test_blocked_by_guard,
                        resources.getString(reason.shortRes),
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}

/**
 * Traditional 2D Saturation-Value picker box.
 * Horizontal axis maps to Saturation (0 = white, 1 = full hue).
 * Vertical axis maps to Value (0 = black, 1 = maximum brightness).
 */
@Composable
private fun SaturationValueBox(
    hue: Float,
    sat: Float,
    value: Float,
    onSatValChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.coerceAtLeast(1)
                    val h = size.height.coerceAtLeast(1)
                    val s = (down.position.x / w).coerceIn(0f, 1f)
                    val v = (1f - down.position.y / h).coerceIn(0f, 1f)
                    onSatValChange(s, v)

                    drag(down.id) { change ->
                        change.consume()
                        val ds = (change.position.x / w).coerceIn(0f, 1f)
                        val dv = (1f - change.position.y / h).coerceIn(0f, 1f)
                        onSatValChange(ds, dv)
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val pureHue = Color(Renderer.hsv(hue, 1f, 1f))

        // 1. Draw pure hue base
        drawRect(pureHue)

        // 2. Horizontal gradient: White (S=0) to Transparent (S=1)
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.White, Color.Transparent),
                startX = 0f,
                endX = w,
            )
        )

        // 3. Vertical gradient: Transparent (V=1) to Black (V=0)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startY = 0f,
                endY = h,
            )
        )

        // 4. Selector crosshair
        val thumbX = (sat * w).coerceIn(0f, w)
        val thumbY = ((1f - value) * h).coerceIn(0f, h)

        drawCircle(
            color = Color.White,
            radius = 10.dp.toPx(),
            center = Offset(thumbX, thumbY),
            style = Stroke(width = 3.dp.toPx()),
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.75f),
            radius = 7.dp.toPx(),
            center = Offset(thumbX, thumbY),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

/**
 * Traditional color picker dialog featuring:
 * - 2D Saturation-Value spectrum canvas
 * - Full 360° Hue rainbow slider
 * - Live color swatch + quick restore of initial color
 * - Editable Hex code field
 * - Quick-select preset swatches
 */
@Composable
fun ColorPickerDialog(
    initialColor: Int,
    title: String,
    onDismiss: () -> Unit,
    onColorSelected: (Int) -> Unit,
) {
    val initialHsv = remember(initialColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialColor, it) }
    }
    var hue by remember(initialColor) { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember(initialColor) { mutableFloatStateOf(initialHsv[1]) }
    var value by remember(initialColor) { mutableFloatStateOf(initialHsv[2]) }

    val currentColor = remember(hue, sat, value) {
        android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    }

    var hexInput by remember(currentColor) {
        mutableStateOf(String.format("%06X", currentColor and 0xFFFFFF))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 1. Traditional 2D Saturation-Value box
                SaturationValueBox(
                    hue = hue,
                    sat = sat,
                    value = value,
                    onSatValChange = { s, v ->
                        sat = s
                        value = v
                    },
                )

                // 2. Hue rainbow slider
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(18.dp)
                            .padding(horizontal = 4.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Red,
                                        Color.Yellow,
                                        Color.Green,
                                        Color.Cyan,
                                        Color.Blue,
                                        Color.Magenta,
                                        Color.Red,
                                    )
                                )
                            )
                    )
                    Slider(
                        value = hue,
                        valueRange = 0f..360f,
                        onValueChange = { hue = it },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 3. Swatches (New & Previous) + Hex Code Input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Current (new) color preview
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(currentColor))
                                .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        )
                        // Previous color preview (tap to restore)
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(initialColor))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable {
                                    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(initialColor, it) }
                                    hue = hsv[0]
                                    sat = hsv[1]
                                    value = hsv[2]
                                }
                        )
                    }

                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { text ->
                            val filtered = text.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.take(6).uppercase()
                            hexInput = filtered
                            if (filtered.length == 6) {
                                val parsed = filtered.toLongOrNull(16)?.toInt()
                                if (parsed != null) {
                                    val fullColor = 0xFF000000.toInt() or parsed
                                    val newHsv = FloatArray(3).also { android.graphics.Color.colorToHSV(fullColor, it) }
                                    hue = newHsv[0]
                                    sat = newHsv[1]
                                    value = newHsv[2]
                                }
                            }
                        },
                        prefix = { Text("#") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(135.dp),
                    )
                }

                // 4. Quick preset color swatches
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PRESET_COLORS.forEach { c ->
                        val selected = (c and 0xFFFFFF) == (currentColor and 0xFFFFFF)
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    if (selected) 2.5.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    CircleShape,
                                )
                                .clickable {
                                    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }
                                    hue = hsv[0]
                                    sat = hsv[1]
                                    value = hsv[2]
                                }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onColorSelected(currentColor)
                onDismiss()
            }) {
                ButtonLabel(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                ButtonLabel(stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * Clickable row that displays the current colour and hex value, opening a traditional
 * 2D Saturation-Value / Hue color picker dialog on click.
 */
@Composable
fun ColorPicker(
    color: Int,
    onColor: (Int) -> Unit,
    label: String = stringResource(R.string.widget_colour),
) {
    var showDialog by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                showDialog = true
            }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Caption(String.format("#%06X", color and 0xFFFFFF))
        }
        Box(
            Modifier
                .size(36.dp)
                .background(Color(color), CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        )
    }

    if (showDialog) {
        ColorPickerDialog(
            initialColor = color,
            title = label,
            onDismiss = { showDialog = false },
            onColorSelected = onColor,
        )
    }
}

/**
 * Eight saturated LED colours derived from the app's current Material You scheme — which, with
 * wallpaper colours on, is derived from the wallpaper itself.
 *
 * The scheme's key hues are taken and their saturation and value pushed up, because container tones
 * are pale by design and pale is nearly invisible on an LED.
 */
@Composable
fun wallpaperLedColours(): List<Int> {
    val scheme = MaterialTheme.colorScheme
    val seeds = listOf(scheme.primary, scheme.secondary, scheme.tertiary)
        .map { android.graphics.Color.valueOf(it.red, it.green, it.blue).toArgb() }
    val hues = seeds.map { c ->
        FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }[0]
    }
    // walk between the key hues so all eight LEDs differ but stay in the wallpaper's family
    return (0 until LED_COUNT).map { i ->
        val t = i.toFloat() / LED_COUNT * hues.size
        val a = hues[t.toInt().coerceAtMost(hues.lastIndex)]
        val b = hues[(t.toInt() + 1).coerceAtMost(hues.lastIndex)]
        val hue = a + (b - a) * (t - t.toInt())
        Renderer.hsv(hue, 1f, 1f)
    }
}
