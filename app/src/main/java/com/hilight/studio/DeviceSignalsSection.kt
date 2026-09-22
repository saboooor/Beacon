package com.hilight.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
fun DeviceSignalsSection(store: Store) {
    val settings by store.deviceSignals.settings.collectAsStateWithLifecycle()
    val isCharging by store.isCharging.collectAsStateWithLifecycle()
    val batteryLevel by store.batteryLevel.collectAsStateWithLifecycle()
    var editingLed by rememberSaveable { mutableIntStateOf(0) }

    SectionTitle(
        stringResource(R.string.device_signals_title),
        trailing = {
            if (isCharging) {
                LivePill(
                    stringResource(R.string.setup_percent, batteryLevel),
                    ok = true,
                )
            }
        },
    )
    PixelCard {
        Caption(stringResource(R.string.device_signals_description))
        ToggleRow(stringResource(R.string.device_signals_charging), settings.chargingEnabled) {
            store.deviceSignals.updateSettings { s -> s.copy(chargingEnabled = it) }
        }
        if (settings.chargingEnabled) {
            ToggleRow(stringResource(R.string.device_signals_gauge), settings.chargingGauge) {
                store.deviceSignals.updateSettings { s -> s.copy(chargingGauge = it) }
            }
            Caption(stringResource(if (settings.chargingGauge) R.string.device_signals_gauge_description else R.string.device_signals_charging_description))

            ToggleRow(stringResource(R.string.device_signals_charging_247), settings.charging247) {
                store.deviceSignals.updateSettings { s ->
                    s.copy(
                        charging247 = it,
                        chargingGauge = if (it) true else s.chargingGauge,
                        chargingBreathe = if (it && !s.chargingBreathe) true else s.chargingBreathe,
                    )
                }
            }
            Caption(stringResource(R.string.device_signals_charging_247_hint))

            if (settings.chargingGauge || settings.charging247) {
                ToggleRow(stringResource(R.string.setup_charging_breathe), settings.chargingBreathe) {
                    store.deviceSignals.updateSettings { s -> s.copy(chargingBreathe = it) }
                }
                Caption(stringResource(R.string.setup_charging_breathe_hint))
            }

            ToggleRow(stringResource(R.string.device_signals_charging_per_led), settings.chargingPerLedEnabled) {
                store.deviceSignals.updateSettings { s -> s.copy(chargingPerLedEnabled = it) }
            }
            if (settings.chargingPerLedEnabled) {
                Caption(stringResource(R.string.device_signals_charging_per_led_hint))

                val previewColors = remember(batteryLevel, settings.chargingPerLed, isCharging, settings.fullPercent) {
                    chargingGaugeLook(settings, if (isCharging) batteryLevel else 100).perLed
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
                    settings.chargingPerLed.forEachIndexed { i, c ->
                        LedSwatch(
                            color = c,
                            selected = i == editingLed,
                            modifier = Modifier.weight(1f),
                        ) { editingLed = i }
                    }
                }

                ColorPicker(
                    color = settings.chargingPerLed.getOrElse(editingLed) { Store.batteryGradientColor(editingLed, LED_COUNT) },
                    onColor = { c ->
                        val updated = settings.chargingPerLed.toMutableList()
                        if (editingLed in updated.indices) {
                            updated[editingLed] = c
                            store.deviceSignals.updateSettings { s -> s.copy(chargingPerLed = updated) }
                        }
                    },
                    label = stringResource(R.string.style_led_number, editingLed + 1),
                )

                FilledTonalButton(
                    onClick = {
                        store.deviceSignals.updateSettings { s -> s.copy(chargingPerLed = Store.defaultBatteryColors()) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ButtonLabel(stringResource(R.string.setup_charging_reset_colors))
                }
            } else {
                ColorPicker(
                    settings.chargingColor,
                    { color -> store.deviceSignals.updateSettings { it.copy(chargingColor = color) } },
                    stringResource(R.string.device_signals_charging_color),
                )
            }

            ColorPicker(
                settings.chargedColor,
                { color -> store.deviceSignals.updateSettings { it.copy(chargedColor = color) } },
                stringResource(R.string.device_signals_charged_color),
            )
            Caption(stringResource(R.string.device_signals_full_percent, settings.fullPercent))
            Slider(
                value = settings.fullPercent.toFloat(),
                onValueChange = { percent ->
                    store.deviceSignals.updateSettings { it.copy(fullPercent = percent.roundToInt()) }
                },
                valueRange = 1f..100f,
                steps = 98,
            )
        }
        ToggleRow(stringResource(R.string.device_signals_dnd), settings.dndEnabled) {
            store.deviceSignals.updateSettings { s -> s.copy(dndEnabled = it) }
        }
        if (settings.dndEnabled) {
            Caption(stringResource(R.string.device_signals_dnd_description))
            ColorPicker(settings.dndColor, { color -> store.deviceSignals.updateSettings { it.copy(dndColor = color) } })
        }
        ToggleRow(stringResource(R.string.device_signals_calls), settings.callsEnabled) {
            store.deviceSignals.updateSettings { s -> s.copy(callsEnabled = it) }
        }
        if (settings.callsEnabled) {
            Caption(stringResource(R.string.device_signals_calls_description))
            ColorPicker(settings.callColor, { color -> store.deviceSignals.updateSettings { it.copy(callColor = color) } })
        }
    }
}
