package com.hilight.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DeviceSignalsSection(store: Store) {
    val settings by store.deviceSignals.settings.collectAsStateWithLifecycle()
    PixelCard {
        SectionTitle(stringResource(R.string.device_signals_title))
        Caption(stringResource(R.string.device_signals_description))
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
