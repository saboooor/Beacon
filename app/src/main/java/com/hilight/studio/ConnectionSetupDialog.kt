package com.hilight.studio

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ConnectionSetupDialog(
    store: Store,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val transport by store.transport.collectAsStateWithLifecycle()
    val status by store.status.collectAsStateWithLifecycle()
    val active by store.activeTransport.collectAsStateWithLifecycle()
    val rootState by store.root.state.collectAsStateWithLifecycle()
    val shizukuState by store.shizuku.state.collectAsStateWithLifecycle()
    val rootPresent = rootState in setOf(
        RootBackend.State.AVAILABLE,
        RootBackend.State.REQUESTING,
        RootBackend.State.STARTING,
        RootBackend.State.RUNNING,
    )
    val rendererConnected = store.isRendererConnectedForUi(status)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.setup_privileged_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                LivePill(
                    text = if (rendererConnected) {
                        stringResource(
                            R.string.main_connected_pill,
                            status.ledCount,
                            stringResource(active.labelRes),
                        )
                    } else {
                        stringResource(R.string.main_not_connected)
                    },
                    ok = rendererConnected,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (rendererConnected) {
                    PixelCard(tone = 0) {
                        SafetyDetails(status)
                    }
                }

                if (rootPresent) {
                    PixelCard(tone = 2) {
                        SectionTitle(
                            stringResource(R.string.setup_root_title),
                            trailing = {
                                LivePill(
                                    stringResource(
                                        if (rootState == RootBackend.State.RUNNING)
                                            R.string.setup_root_active else R.string.setup_root_available
                                    ),
                                    ok = true,
                                )
                            },
                        )
                        Caption(
                            stringResource(
                                when (rootState) {
                                    RootBackend.State.AVAILABLE -> R.string.setup_root_available_body
                                    RootBackend.State.REQUESTING -> R.string.setup_root_requesting_body
                                    RootBackend.State.STARTING -> R.string.setup_root_starting_body
                                    else -> R.string.setup_root_active_body
                                }
                            )
                        )
                        if (rootState == RootBackend.State.RUNNING && !status.alive) {
                            Caption(stringResource(R.string.setup_led_cleanup_renderer_unavailable))
                            TextButton(onClick = store::retryRoot) {
                                ButtonLabel(stringResource(R.string.setup_root_retry))
                            }
                        }
                    }
                } else {
                    PixelCard(tone = 2) {
                        SectionTitle(stringResource(R.string.setup_privileged_title))
                        Caption(stringResource(R.string.setup_privileged_body))
                        val selectable = listOf(Transport.AUTO, Transport.SHIZUKU, Transport.ADB)
                        val transportLabels = selectable.associateWith { stringResource(it.labelRes) }
                        SegmentedSelector(
                            options = selectable,
                            selected = transport.takeIf { it in selectable } ?: Transport.AUTO,
                            label = { transportLabels.getValue(it) },
                            onSelect = { store.setTransport(it) },
                        )
                        if (transport == Transport.AUTO) Caption(stringResource(R.string.setup_transport_auto_note))
                        if (rootState == RootBackend.State.DENIED || rootState == RootBackend.State.ERROR) {
                            Caption(
                                store.root.errorText()
                                    ?: stringResource(R.string.setup_root_error_body)
                            )
                            TextButton(onClick = store::retryRoot) {
                                ButtonLabel(stringResource(R.string.setup_root_retry))
                            }
                        }
                    }

                    AnimatedContent(
                        targetState = transport,
                        transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(120))) },
                        label = "transportCards",
                    ) { t ->
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (t != Transport.ADB) ShizukuCard(store, shizukuState)
                            if (t != Transport.SHIZUKU) AdbCard(ctx)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                ButtonLabel(stringResource(R.string.common_close))
            }
        },
    )
}

@Composable
fun ShizukuCard(store: Store, state: ShizukuBackend.State) {
    val ctx = LocalContext.current
    PixelCard {
        SectionTitle(
            stringResource(R.string.transport_shizuku),
            trailing = {
                val pill = when (state) {
                    ShizukuBackend.State.CONNECTED -> R.string.shizuku_state_connected
                    ShizukuBackend.State.CONNECTING -> R.string.shizuku_state_connecting
                    ShizukuBackend.State.NEEDS_PERMISSION -> R.string.shizuku_state_needs_permission
                    ShizukuBackend.State.NOT_RUNNING -> R.string.shizuku_state_not_running
                    ShizukuBackend.State.NOT_INSTALLED -> R.string.shizuku_state_not_installed
                    ShizukuBackend.State.FAILED -> R.string.shizuku_state_failed
                }
                LivePill(stringResource(pill), state == ShizukuBackend.State.CONNECTED)
            },
        )

        Caption(stringResource(R.string.shizuku_reattach_note))

        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn(tween(160)).togetherWith(fadeOut(tween(100))) },
            label = "shizukuState",
        ) { s ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (s) {
                    ShizukuBackend.State.NOT_INSTALLED -> {
                        Caption(stringResource(R.string.shizuku_not_installed_body))
                        Button(onClick = { openShizukuListing(ctx) }) {
                            ButtonLabel(stringResource(R.string.shizuku_get))
                        }
                    }

                    ShizukuBackend.State.NOT_RUNNING -> {
                        Caption(stringResource(R.string.shizuku_not_running_body))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { openShizuku(ctx) }) {
                                ButtonLabel(stringResource(R.string.shizuku_open))
                            }
                            TextButton(onClick = { store.shizuku.refresh() }) {
                                ButtonLabel(stringResource(R.string.shizuku_check_again))
                            }
                        }
                    }

                    ShizukuBackend.State.NEEDS_PERMISSION -> {
                        Caption(stringResource(R.string.shizuku_needs_permission_body))
                        Button(onClick = { store.shizuku.requestPermission() }) {
                            ButtonLabel(stringResource(R.string.shizuku_request_access))
                        }
                    }

                    ShizukuBackend.State.CONNECTED -> {
                        Caption(stringResource(R.string.shizuku_connected_body))
                        TextButton(onClick = { store.disconnectShizuku() }) {
                            ButtonLabel(stringResource(R.string.shizuku_disconnect))
                        }
                    }

                    else -> {
                        Caption(
                            store.shizuku.errorRes()?.let { stringResource(it) }
                                ?: store.shizuku.errorText()
                                ?: stringResource(R.string.shizuku_unreachable)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { store.shizuku.refresh() }) {
                                ButtonLabel(stringResource(R.string.shizuku_retry))
                            }
                            TextButton(onClick = { openShizuku(ctx) }) {
                                ButtonLabel(stringResource(R.string.shizuku_open))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdbCard(ctx: Context) {
    PixelCard {
        SectionTitle(stringResource(R.string.adb_title))
        Caption(stringResource(R.string.adb_body))
        Text(
            ADB_COMMAND,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.shapes.medium,
                )
                .padding(14.dp),
        )
        Caption(stringResource(R.string.adb_shells_note))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { copy(ctx, ADB_COMMAND, R.string.adb_copied) }) {
                ButtonLabel(stringResource(R.string.adb_copy))
            }
            TextButton(onClick = { copy(ctx, ADB_COMMAND_CMD, R.string.adb_copied_cmd) }) {
                ButtonLabel(stringResource(R.string.adb_copy_cmd))
            }
        }
        Caption(stringResource(R.string.adb_verify_note))
        TextButton(onClick = { share(ctx, ADB_COMMAND) }) {
            ButtonLabel(stringResource(R.string.adb_send))
        }
    }
}
