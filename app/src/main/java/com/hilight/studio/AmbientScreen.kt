package com.hilight.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The always-on look: pattern, colours, timing. */
@Composable
fun AmbientScreen(store: Store) {
    val ambient by store.ambient.collectAsStateWithLifecycle()
    val enabled by store.enabled.collectAsStateWithLifecycle()
    val currentMedia by store.currentMedia.collectAsStateWithLifecycle()
    var editingLed by rememberSaveable { mutableIntStateOf(0) }

    MediaCard(store)
    TryAnEffectCard(store)

    PixelCard(tone = 2) {
        SectionTitle(stringResource(R.string.style_always_on_style))
        PatternCarousel(
            selected = ambient.pattern,
            options = Pattern.entries,
            onSelect = { store.setAmbient(ambient.copy(pattern = it)) },
            ambient = ambient,
        )
        if (!enabled) {
            Text(
                stringResource(R.string.style_control_off_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    // options morph rather than jump when the pattern changes
    AnimatedContent(
        targetState = ambient.pattern,
        transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(120))) },
        label = "patternOptions",
    ) { pattern ->
        Column {
            when (pattern) {
                Pattern.RANDOM -> PixelCard {
                    SectionTitle(stringResource(R.string.pattern_random))
                    PixelSlider(
                        stringResource(R.string.style_change_every),
                        ambient.randomIntervalMs.toFloat(),
                        150f..8000f,
                        { store.setAmbient(ambient.copy(randomIntervalMs = it.toInt())) },
                        typeInSeconds = true,
                    ) {
                        // shown to a tenth of a second, so dragging does not spray digits
                        val tenths = (it / 100).toInt() / 10f
                        stringResource(R.string.duration_seconds_fraction, tenths.toString())
                    }
                    ToggleRow(stringResource(R.string.style_colour_per_led), ambient.randomPerLed) {
                        store.setAmbient(ambient.copy(randomPerLed = it))
                    }
                    ToggleRow(stringResource(R.string.style_fade_between_colours), ambient.randomSmooth) {
                        store.setAmbient(ambient.copy(randomSmooth = it))
                    }
                    PixelSlider(
                        stringResource(R.string.style_saturation),
                        ambient.randomSaturation,
                        0.2f..1f,
                        { store.setAmbient(ambient.copy(randomSaturation = it)) },
                    ) { stringResource(R.string.style_percent, (it * 100).toInt()) }
                }

                Pattern.RAINBOW -> PixelCard {
                    SectionTitle(stringResource(R.string.pattern_rainbow))
                    ToggleRow(stringResource(R.string.style_rainbow_spread), ambient.rainbowSpread) {
                        store.setAmbient(ambient.copy(rainbowSpread = it))
                    }
                    Caption(stringResource(R.string.style_rainbow_spread_off))
                }

                Pattern.CUSTOM -> PixelCard {
                    SectionTitle(stringResource(R.string.style_per_led_colours))
                    Caption(stringResource(R.string.style_per_led_hint))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        ambient.perLed.forEachIndexed { i, c ->
                            LedSwatch(
                                color = c,
                                selected = i == editingLed,
                                modifier = Modifier.weight(1f),
                            ) { editingLed = i }
                        }
                    }
                    ColorPicker(
                        color = ambient.perLed[editingLed],
                        onColor = { c ->
                            store.setAmbient(
                                ambient.copy(
                                    perLed = ambient.perLed.toMutableList().also { it[editingLed] = c })
                            )
                        },
                        label = stringResource(R.string.style_led_number, editingLed + 1),
                    )
                    PixelSlider(
                        stringResource(R.string.style_rotate_around_array),
                        ambient.rotateMs.toFloat(),
                        0f..2000f,
                        { store.setAmbient(ambient.copy(rotateMs = it.toInt())) },
                    ) {
                        if (it < 50) stringResource(R.string.style_rotate_off)
                        else stringResource(R.string.duration_ms, it.toInt())
                    }
                    if (ambient.rotateMs >= 50) {
                        ToggleRow(
                            stringResource(R.string.style_rotate_fade),
                            ambient.rotateFade,
                        ) { store.setAmbient(ambient.copy(rotateFade = it)) }
                    }
                    val wallpaper = wallpaperLedColours()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                store.setAmbient(
                                    ambient.copy(
                                        perLed = List(LED_COUNT) { i -> Renderer.hsv(i * 360f / LED_COUNT) })
                                )
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        ) { ButtonLabel(stringResource(Pattern.RAINBOW.shortLabelRes)) }
                        FilledTonalButton(
                            onClick = { store.setAmbient(ambient.copy(perLed = wallpaper)) },
                            modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        ) { ButtonLabel(stringResource(R.string.style_wallpaper)) }
                        if (currentMedia != null) {
                            FilledTonalButton(
                                onClick = { store.applyMediaColorsToAmbient() },
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            ) { ButtonLabel(stringResource(R.string.style_media_button)) }
                        }
                        FilledTonalButton(
                            onClick = {
                                store.setAmbient(ambient.copy(perLed = List(LED_COUNT) { ambient.color }))
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        ) { ButtonLabel(stringResource(R.string.pattern_solid)) }
                    }
                }

                Pattern.GRADIENT -> PixelCard {
                    SectionTitle(stringResource(R.string.pattern_gradient))
                    ColorPicker(
                        ambient.color,
                        { store.setAmbient(ambient.copy(color = it)) },
                        stringResource(R.string.style_gradient_start),
                    )
                    ColorPicker(
                        ambient.secondColor,
                        { store.setAmbient(ambient.copy(secondColor = it)) },
                        stringResource(R.string.style_gradient_end),
                    )
                    if (currentMedia != null) {
                        FilledTonalButton(
                            onClick = { store.applyMediaGradientToAmbient() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            ButtonLabel(stringResource(R.string.style_use_media_colors))
                        }
                    }
                    PixelSlider(
                        stringResource(R.string.style_rotate_around_array),
                        ambient.rotateMs.toFloat(),
                        0f..2000f,
                        { store.setAmbient(ambient.copy(rotateMs = it.toInt())) },
                    ) {
                        if (it < 50) stringResource(R.string.style_rotate_off)
                        else stringResource(R.string.duration_ms, it.toInt())
                    }
                    if (ambient.rotateMs >= 50) {
                        ToggleRow(
                            stringResource(R.string.style_rotate_fade),
                            ambient.rotateFade,
                        ) { store.setAmbient(ambient.copy(rotateFade = it)) }
                    }
                }

                Pattern.OFF -> PixelCard {
                    SectionTitle(stringResource(R.string.pattern_off))
                    Caption(stringResource(R.string.style_off_body))
                }

                else -> PixelCard {
                    SectionTitle(stringResource(R.string.style_colour))
                    ColorPicker(ambient.color, { store.setAmbient(ambient.copy(color = it)) })
                }
            }

            if (pattern != Pattern.OFF) {
                PixelCard {
                    SectionTitle(stringResource(R.string.style_timing))
                    if (pattern.usesSpeed) {
                        PixelSlider(
                            stringResource(R.string.style_time_per_cycle),
                            ambient.speedMs.toFloat(),
                            150f..8000f,
                            { store.setAmbient(ambient.copy(speedMs = it.toInt())) },
                            typeInSeconds = true,
                        ) { formatDuration(it.toInt()) }
                        pattern.cycleMeaningRes?.let { Caption(stringResource(it)) }
                        Caption(stringResource(R.string.style_shorter_is_faster))
                    }
                    PixelSlider(
                        stringResource(R.string.style_brightness),
                        ambient.brightness,
                        0.02f..1f,
                        { store.setAmbient(ambient.copy(brightness = it)) },
                    ) { stringResource(R.string.style_percent, (it * 100).toInt()) }
                    Caption(stringResource(R.string.style_brightness_note))
                }
            }
        }
    }

    PresetsCard(store)
    ChargingBatteryCard(store)
    DeviceSignalsSection(store)
}

/**
 * White-light effects have no colour of their own, so give their tiles a readable accent.
 */
@Composable
private fun tileAccent(pattern: Pattern, color: Int): Color = when {
    color != 0xFFFFFFFF.toInt() -> Color(color)
    pattern == Pattern.RAINBOW -> Color(0xFF7C4DFF)
    else -> Color(0xFFFFB300)
}

private data class EffectTest(val labelRes: Int, val pattern: Pattern, val color: Int)

/** One-tap effects to try out every pattern. */
@Composable
private fun TryAnEffectCard(store: Store) {
    val launchPreview = rememberPreviewLauncher(store)
    val enabled by store.enabled.collectAsStateWithLifecycle()
    val ambient by store.ambient.collectAsStateWithLifecycle()
    val status by store.status.collectAsStateWithLifecycle()

    val tests: List<EffectTest> = listOf(
        EffectTest(Pattern.RAINBOW.shortLabelRes, Pattern.RAINBOW, 0xFFFFFFFF.toInt()),
        EffectTest(R.string.live_test_random, Pattern.RANDOM, 0xFFFFFFFF.toInt()),
        EffectTest(Pattern.STROBE.shortLabelRes, Pattern.STROBE, 0xFFFFFFFF.toInt()),
        EffectTest(Pattern.COMET.shortLabelRes, Pattern.COMET, 0xFF00E5FF.toInt()),
        EffectTest(Pattern.PULSE.shortLabelRes, Pattern.PULSE, 0xFFFF1744.toInt()),
        EffectTest(Pattern.HEARTBEAT.shortLabelRes, Pattern.HEARTBEAT, 0xFFFF1744.toInt()),
        EffectTest(Pattern.BREATHE.shortLabelRes, Pattern.BREATHE, 0xFF7C4DFF.toInt()),
        EffectTest(Pattern.WAVE.shortLabelRes, Pattern.WAVE, 0xFF00E676.toInt()),
        EffectTest(Pattern.RADAR.shortLabelRes, Pattern.RADAR, 0xFF00E5FF.toInt()),
        EffectTest(Pattern.BOUNCE.shortLabelRes, Pattern.BOUNCE, 0xFFFF9100.toInt()),
        EffectTest(Pattern.CONVERGE.shortLabelRes, Pattern.CONVERGE, 0xFFE040FB.toInt()),
        EffectTest(Pattern.GLITCH.shortLabelRes, Pattern.GLITCH, 0xFF00E5FF.toInt()),
        EffectTest(Pattern.METER.shortLabelRes, Pattern.METER, 0xFF00E676.toInt()),
        EffectTest(Pattern.BLINK.shortLabelRes, Pattern.BLINK, 0xFFFFD600.toInt()),
        EffectTest(Pattern.CHASE.shortLabelRes, Pattern.CHASE, 0xFF00B0FF.toInt()),
    )

    PixelCard {
        SectionTitle(stringResource(R.string.live_tests_title))
        Caption(stringResource(R.string.live_tests_caption))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tests.chunked(3).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { test ->
                        val tileAmbient = remember(test.pattern, test.color) {
                            Ambient(
                                pattern = test.pattern,
                                color = test.color,
                                speedMs = 1200,
                                brightness = 1f,
                                randomIntervalMs = 500,
                            )
                        }
                        PixelTile(
                            label = stringResource(test.labelRes),
                            accent = tileAccent(test.pattern, test.color),
                            enabled = enabled && status.alive,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (test.pattern == Pattern.RANDOM) {
                                    launchPreview(test.pattern, test.color, 1200, 1f, 4_000, ambient.copy(pattern = Pattern.RANDOM))
                                } else {
                                    launchPreview(test.pattern, test.color, 1200, 1f, 4_000, null)
                                }
                            },
                        ) {
                            HiLightDiscPreview(
                                pattern = test.pattern,
                                cfg = tileAmbient,
                                active = enabled && status.alive,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Saved looks: apply with a tap, save the current one, and move them between devices as JSON. */
@Composable
private fun PresetsCard(store: Store) {
    val ctx = LocalContext.current
    val presets by store.presets.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }

    PixelCard {
        SectionTitle(
            stringResource(R.string.style_presets),
            trailing = { Caption(stringResource(R.string.style_presets_saved, presets.size)) },
        )
        if (presets.isEmpty()) {
            Caption(stringResource(R.string.style_presets_empty))
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { preset ->
                    Row(
                        Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                CircleShape,
                            )
                            .clickable { store.applyPreset(preset) }
                            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(preset.name, style = MaterialTheme.typography.labelLarge)
                        Text(
                            "✕",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { store.deletePreset(preset) }
                                .padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = { name = ""; naming = true },
                modifier = Modifier.weight(1f),
            ) { ButtonLabel(stringResource(R.string.common_save)) }
            FilledTonalButton(
                onClick = { shareText(ctx, store.exportPresets()) },
                enabled = presets.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { ButtonLabel(stringResource(R.string.style_export)) }
            FilledTonalButton(
                onClick = { importText = ""; importing = true },
                modifier = Modifier.weight(1f),
            ) { ButtonLabel(stringResource(R.string.style_import)) }
        }
    }

    if (naming) {
        // An empty field falls back to a numbered name. Resolved here rather than in the store,
        // because the store has no business holding a piece of copy in one language.
        val fallbackName = stringResource(R.string.style_preset_default_name, presets.size + 1)
        AlertDialog(
            onDismissRequest = { naming = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.style_name_this_look)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.style_name_field)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.savePreset(name.trim().ifEmpty { fallbackName })
                    naming = false
                }) { ButtonLabel(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) {
                    ButtonLabel(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (importing) {
        AlertDialog(
            onDismissRequest = { importing = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.style_paste_exported_presets)) },
            text = {
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    label = { Text(stringResource(R.string.style_import_json_field)) },
                    modifier = Modifier.heightIn(max = 220.dp),
                )
            },
            confirmButton = {
                val res = LocalResources.current
                TextButton(onClick = {
                    val added = store.importPresets(importText)
                    Toast.makeText(
                        ctx,
                        // Read through LocalResources rather than the context: a Context captured in
                        // composition is not invalidated when the configuration changes, so after a
                        // locale switch this toast would have been built from the previous language's
                        // resources.
                        if (added == null) res.getString(R.string.style_import_failed)
                        else res.getString(R.string.style_import_count, added),
                        Toast.LENGTH_SHORT,
                    ).show()
                    importing = false
                }) { ButtonLabel(stringResource(R.string.style_import)) }
            },
            dismissButton = {
                TextButton(onClick = { importing = false }) {
                    ButtonLabel(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

private fun shareText(ctx: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(
        android.content.Intent.createChooser(send, ctx.getString(R.string.style_export_chooser))
    )
}

/** Scrolling pattern picker whose selection animates in colour and size. */
@Composable
fun PatternCarousel(
    selected: Pattern,
    options: List<Pattern>,
    onSelect: (Pattern) -> Unit,
    ambient: Ambient? = null,
) {
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()

    // keep the current pattern on screen, including when it is restored from settings
    LaunchedEffect(selected, options) {
        val index = options.indexOf(selected)
        if (index >= 0) listState.animateScrollToItem(index, scrollOffset = -120)
    }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options, key = { it.key }) { p ->
            val isSelected = p == selected
            val bg by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                label = "chipBg",
            )
            val fg by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "chipFg",
            )
            val scale by animateFloatAsState(
                if (isSelected) 1.04f else 1f,
                spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium),
                label = "chipScale",
            )
            val chipColor = if (ambient != null && ambient.color != 0) ambient.color else 0xFF00E5FF.toInt()
            val chipAmbient = remember(p, ambient, chipColor) {
                (ambient ?: Ambient(color = chipColor)).copy(
                    pattern = p,
                    color = chipColor,
                    speedMs = (ambient?.speedMs ?: 1200).coerceIn(800, 2000),
                    randomIntervalMs = if (p == Pattern.RANDOM) 500 else (ambient?.randomIntervalMs ?: 1500),
                )
            }
            Box(
                Modifier
                    .scale(scale)
                    .clip(CircleShape)
                    .background(bg)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelect(p)
                    }
                    .padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HiLightDiscPreview(
                        pattern = p,
                        cfg = chipAmbient,
                        active = true,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(stringResource(p.labelRes), style = MaterialTheme.typography.labelLarge, color = fg)
                }
            }
        }
    }
}

@Composable
private fun LedSwatch(
    color: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        if (selected) 1.1f else 1f,
        spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium),
        label = "ledSwatch",
    )
    Box(
        modifier
            .scale(scale)
            .aspectRatio(1f)
            .background(Color(color), CircleShape)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                CircleShape,
            )
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {}
}

@Composable
private fun ChargingBatteryCard(store: Store) {
    val chargingIndicator by store.chargingIndicator.collectAsStateWithLifecycle()
    val chargingBreathe by store.chargingBreathe.collectAsStateWithLifecycle()
    val isCharging by store.isCharging.collectAsStateWithLifecycle()
    val batteryLevel by store.batteryLevel.collectAsStateWithLifecycle()
    val chargingPerLed by store.chargingPerLed.collectAsStateWithLifecycle()
    var editingLed by rememberSaveable { mutableIntStateOf(0) }

    PixelCard {
        SectionTitle(
            stringResource(R.string.setup_charging_title),
            trailing = {
                if (isCharging) {
                    LivePill(
                        stringResource(R.string.setup_percent, batteryLevel),
                        ok = true,
                    )
                }
            },
        )
        Caption(stringResource(R.string.setup_charging_hint))
        ToggleRow(stringResource(R.string.setup_charging_enabled), chargingIndicator) {
            store.setChargingIndicator(it)
        }
        if (chargingIndicator) {
            val previewColors = remember(batteryLevel, chargingPerLed, isCharging) {
                Store.computeChargingPerLed(if (isCharging) batteryLevel else 100, chargingPerLed)
            }
            val previewAmbient = remember(previewColors) {
                Ambient(pattern = Pattern.CUSTOM, perLed = previewColors)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                HiLightDiscPreview(
                    pattern = Pattern.CUSTOM,
                    cfg = previewAmbient,
                    active = true,
                    modifier = Modifier.size(90.dp),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                chargingPerLed.forEachIndexed { i, c ->
                    LedSwatch(
                        color = c,
                        selected = i == editingLed,
                        modifier = Modifier.weight(1f),
                    ) { editingLed = i }
                }
            }

            ColorPicker(
                color = chargingPerLed.getOrElse(editingLed) { Store.batteryGradientColor(editingLed, LED_COUNT) },
                onColor = { c -> store.setChargingLedColor(editingLed, c) },
                label = stringResource(R.string.style_led_number, editingLed + 1),
            )

            FilledTonalButton(
                onClick = { store.resetChargingPerLed() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                ButtonLabel(stringResource(R.string.setup_charging_reset_colors))
            }

            ToggleRow(stringResource(R.string.setup_charging_breathe), chargingBreathe) {
                store.setChargingBreathe(it)
            }
            Caption(stringResource(R.string.setup_charging_breathe_hint))
        }
    }
}
