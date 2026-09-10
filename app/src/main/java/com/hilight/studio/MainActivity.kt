package com.hilight.studio

import android.os.Bundle
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        val store = Store.get(this)
        setContent {
            val dynamic by store.dynamicColor.collectAsStateWithLifecycle()
            HiLightTheme(dynamicColor = dynamic) {
                App(store)
            }
        }
    }

    /** Without this the app's own notifications are dropped, including the Setup self test. */
    private fun requestNotificationPermissionIfNeeded() {
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Store.get(this).apply {
            syncForegroundWatcher()
            refreshStatus()
            mediaTracker.startListening()
        }
    }

    /** A test the user started by hand must not outlive the screen they started it from. */
    override fun onStop() {
        super.onStop()
        Store.get(this).stopPreview()
    }
}

private enum class Tab(@StringRes val labelRes: Int, val icon: ImageVector) {
    AMBIENT(R.string.tab_style, Icons.Rounded.Tune),
    APPS(R.string.tab_apps, Icons.Rounded.Apps),
    SETUP(R.string.tab_setup, Icons.Rounded.Settings),
}

@Composable
internal fun SafetyDetails(status: HelperStatus) {
    var elapsedMs by remember(status.ambientRemainingMs, status.ambientHeld) { mutableLongStateOf(0L) }
    LaunchedEffect(status.ambientRemainingMs, status.ambientHeld) {
        while (true) {
            delay(500)
            elapsedMs += 500
        }
    }
    val remaining = (status.ambientRemainingMs - elapsedMs).coerceAtLeast(0)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val safetyLine = when {
            status.resting -> stringResource(R.string.live_safety_resting)
            !status.safetyGuards -> stringResource(R.string.live_safety_disabled)
            status.ambientHeld || remaining == 0L -> stringResource(R.string.live_safety_timed_out)
            else -> stringResource(R.string.live_safety_countdown, remaining / 1000, status.dutyPct)
        }
        Text(safetyLine, style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(
                R.string.live_renderer_pid,
                status.pid,
                stringResource(
                    if (status.sessionOpen) R.string.live_session_open
                    else R.string.live_session_closed
                ),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(store: Store) {
    // saved, so a rotation or a recreated activity does not drop the user back on Live
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tab = Tab.entries[tabIndex.coerceIn(0, Tab.entries.lastIndex)]
    val status by store.status.collectAsStateWithLifecycle()
    val active by store.activeTransport.collectAsStateWithLifecycle()
    val enabled by store.enabled.collectAsStateWithLifecycle()
    val ambient by store.ambient.collectAsStateWithLifecycle()
    val previewLook by store.previewLook.collectAsStateWithLifecycle()
    val suppression by store.suppression.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var showStatusDialog by remember { mutableStateOf(false) }
    var showConnectionDialog by remember { mutableStateOf(false) }

    // Tied to the lifecycle, not just the composition: a plain LaunchedEffect keeps its coroutine
    // running once the activity stops, so this polled the helper over binder and file I/O every 1.5s
    // in the background, for a screen nobody was looking at.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                store.refreshStatus()
                delay(1500)
            }
        }
    }

    val profile = rememberDeviceProfile()
    val modelName = profile.labelRes?.let { stringResource(it) } ?: profile.label

    val shown = previewLook ?: ambient
    val activeLight = (enabled || previewLook != null) && status.alive
    val statusText = when {
        !profile.hasHiLight -> stringResource(R.string.live_status_unavailable)
        previewLook != null -> stringResource(
            R.string.live_status_testing,
            stringResource(shown.pattern.labelRes),
        )
        !status.alive -> stringResource(R.string.tile_no_renderer)
        !enabled -> stringResource(R.string.live_status_system)
        status.resting -> stringResource(R.string.tile_resting)
        suppression != null -> stringResource(suppression!!.shortRes)
        status.ambientHeld -> stringResource(R.string.tile_timed_out)
        else -> stringResource(
            R.string.live_status_on,
            stringResource(ambient.pattern.labelRes),
        )
    }

    if (showConnectionDialog) {
        ConnectionSetupDialog(
            store = store,
            onDismiss = { showConnectionDialog = false },
        )
    }

    if (showStatusDialog) {
        AlertDialog(
            onDismissRequest = { showStatusDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HiLightDiscPreview(
                        pattern = if (activeLight) shown.pattern else Pattern.OFF,
                        cfg = shown,
                        active = activeLight,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.app_name))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        modelName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        statusText,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (enabled || previewLook != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    suppression?.let {
                        Text(
                            stringResource(
                                when (it) {
                                    Suppression.QUIET_HOURS -> R.string.live_suppressed_quiet_hours
                                    Suppression.LOW_BATTERY -> R.string.live_suppressed_low_battery
                                    Suppression.POWER_SAVER -> R.string.live_suppressed_power_saver
                                    Suppression.SCREEN_ON -> R.string.live_suppressed_screen_on
                                    Suppression.NOT_FACE_DOWN -> R.string.live_suppressed_not_face_down
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (!profile.hasHiLight) {
                        Text(
                            stringResource(R.string.live_hint_no_array, modelName),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else if (!status.alive) {
                        Text(
                            stringResource(R.string.live_hint_no_renderer),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else if (status.resting) {
                        Text(
                            stringResource(R.string.live_safety_resting),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else if (enabled) {
                        SafetyDetails(status)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStatusDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                modifier = Modifier.padding(vertical = 4.dp),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showStatusDialog = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        HiLightDiscPreview(
                            pattern = if (activeLight) shown.pattern else Pattern.OFF,
                            cfg = shown,
                            active = activeLight,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    modelName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (enabled || previewLook != null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    val rendererConnected = store.isRendererConnectedForUi(status)
                    if (rendererConnected) {
                        LivePill(
                            text = stringResource(
                                R.string.main_connected_pill,
                                status.ledCount,
                                stringResource(active.labelRes),
                            ),
                            ok = true,
                            modifier = Modifier.clickable { showConnectionDialog = true },
                        )
                    } else {
                        FilledTonalButton(
                            onClick = { showConnectionDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            ButtonLabel(stringResource(R.string.common_setup))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = enabled,
                        onCheckedChange = { store.setEnabled(it) },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            if (tab != t) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            tabIndex = t.ordinal
                        },
                        icon = { Icon(t.icon, contentDescription = stringResource(t.labelRes)) },
                        label = { Text(stringResource(t.labelRes)) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { pad ->
        // tabs slide in the direction of travel, like the system's pagers
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(320)) { w -> dir * w / 8 } + fadeIn(tween(220)))
                    .togetherWith(
                        slideOutHorizontally(tween(320)) { w -> -dir * w / 8 } + fadeOut(tween(160))
                    )
            },
            label = "tab",
            modifier = Modifier.padding(pad),
        ) { current ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                when (current) {
                    Tab.AMBIENT -> AmbientScreen(store)
                    Tab.APPS -> AppRulesScreen(store)
                    Tab.SETUP -> SetupScreen(store)
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}
