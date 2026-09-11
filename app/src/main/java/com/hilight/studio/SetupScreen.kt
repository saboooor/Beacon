package com.hilight.studio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Clears any renderer already holding the array, before a new one is started.
 *
 * Only one renderer may drive the LEDs, but nothing stops a second from opening its own session, and
 * a leftover one goes on pushing black — which wins over the live renderer, so the array stays dark
 * while every status readout insists it is working. Two ways in: running the start command twice
 * without a reboot, and a Shizuku user service, which is a daemon and survives both Shizuku being
 * stopped and this app being uninstalled.
 *
 * The reset enumerates `/proc`, accepts only the exact helper entry point or Shizuku process name,
 * sends cooperative TERM, and waits up to 6.5 seconds for exact exit. That exceeds Engine's bounded
 * four-second stop path. Launch is in the same phone-shell command and is skipped on any survivor,
 * so copying the setup command cannot create a second writer after a failed reset.
 */
private const val ADB_PHONE_RESET =
    "live=1; i=0; while [ ${'$'}i -lt 65 ] && [ -n \"${'$'}live\" ]; do live=\"\"; " +
        "for d in /proc/[0-9]*; do p=${'$'}{d#/proc/}; " +
        "c=${'$'}(tr \"\\000\" \" \" < ${'$'}d/cmdline 2>/dev/null); " +
        "if [ -z \"${'$'}c\" ]; then e=${'$'}(readlink ${'$'}d/exe 2>/dev/null); " +
        "x=${'$'}{e##*/}; if [ \"${'$'}x\" = app_process ] || " +
        "[ \"${'$'}x\" = app_process32 ] || " +
        "[ \"${'$'}x\" = app_process64 ]; then exit 1; fi; continue; fi; " +
        "set -- ${'$'}c; " +
        "x=${'$'}{1##*/}; if { { [ \"${'$'}x\" = app_process ] || " +
        "[ \"${'$'}x\" = app_process32 ] || [ \"${'$'}x\" = app_process64 ]; } && " +
        "[ \"${'$'}{2:-x}\" = / ] && " +
        "[ \"${'$'}{3:-x}\" = com.hilight.core.AdbHelper ]; } || " +
        "[ \"${'$'}{1:-x}\" = com.hilight.studio:hilight ]; then " +
        "kill -TERM ${'$'}p 2>/dev/null || exit 1; live=1; fi; done; " +
        "[ -n \"${'$'}live\" ] && sleep 0.1; i=${'$'}((i + 1)); done; " +
        "[ -z \"${'$'}live\" ] || exit 1"

private const val ADB_PHONE_RESET_CMD =
    "live=1; i=0; while [ ${'$'}i -lt 65 ] && [ ${'$'}live = 1 ]; do live=0; " +
        "for d in /proc/[0-9]*; do p=${'$'}{d#/proc/}; " +
        "c=${'$'}(tr '\\000' ' ' < ${'$'}d/cmdline 2>/dev/null); set -- ${'$'}c; " +
        "if [ ${'$'}# -eq 0 ]; then e=${'$'}(readlink ${'$'}d/exe 2>/dev/null); " +
        "x=${'$'}{e##*/}; if [ ${'$'}{x:-none} = app_process ] || " +
        "[ ${'$'}{x:-none} = app_process32 ] || [ ${'$'}{x:-none} = app_process64 ]; " +
        "then exit 1; fi; continue; fi; " +
        "x=${'$'}{1##*/}; if { { [ ${'$'}x = app_process ] || " +
        "[ ${'$'}x = app_process32 ] || [ ${'$'}x = app_process64 ]; } && " +
        "[ ${'$'}{2:-x} = / ] && " +
        "[ ${'$'}{3:-x} = com.hilight.core.AdbHelper ]; } || " +
        "[ ${'$'}{1:-x} = com.hilight.studio:hilight ]; then " +
        "kill -TERM ${'$'}p 2>/dev/null || exit 1; live=1; fi; done; " +
        "[ ${'$'}live = 1 ] && sleep 0.1; i=${'$'}((i + 1)); done; " +
        "[ ${'$'}live = 0 ] || exit 1"

const val ADB_RESET =
    "adb shell '$ADB_PHONE_RESET'"

/**
 * Starts the renderer out of the installed APK.
 *
 * Single quotes matter: they stop the desktop shell touching `$(...)`, so the *phone* resolves the
 * APK path with its own `pm`. Substituting on the desktop instead makes the command shell-specific —
 * it needs a second `adb shell`, it breaks on any adb that ends lines with CRLF (the trailing return
 * lands in CLASSPATH), and it cannot work in a Windows shell at all, where `head` and `cut` are
 * missing. All of those fail silently, with an empty log.
 *
 * Windows Command Prompt has no single quotes; [ADB_COMMAND_CMD] is the same thing with double ones.
 * Quoting keeps `|`, parentheses, redirects, and `&` away from cmd.exe, while cmd.exe leaves `$`
 * alone, so the phone receives and expands the command substitution.
 */
const val ADB_COMMAND =
    "adb shell '$ADB_PHONE_RESET; " +
        "instance=adb-${'$'}(cat /proc/sys/kernel/random/uuid); " +
        "CLASSPATH=${'$'}(pm path com.hilight.studio | head -1 | cut -d: -f2) " +
        "nohup app_process / com.hilight.core.AdbHelper --owner adb " +
        "--instance \"${'$'}instance\" --exclusive > /data/local/tmp/hilight.log 2>&1 &'"

/** The same pair for Windows Command Prompt, which does not understand single quotes. */
const val ADB_COMMAND_CMD =
    "adb shell \"$ADB_PHONE_RESET_CMD; " +
        "instance=adb-${'$'}(cat /proc/sys/kernel/random/uuid); " +
        "CLASSPATH=${'$'}(pm path com.hilight.studio | head -1 | cut -d: -f2) " +
        "nohup app_process / com.hilight.core.AdbHelper --owner adb " +
        "--instance ${'$'}instance --exclusive > /data/local/tmp/hilight.log 2>&1 &\""

@Composable
fun SetupScreen(store: Store) {
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val status by store.status.collectAsStateWithLifecycle()
    val masterEnabled by store.enabled.collectAsStateWithLifecycle()
    val manualCleanupPending by store.manualLedCleanupPending.collectAsStateWithLifecycle()
    val manualCleanupInProgress = manualCleanupPending ||
        (status.blackClearPending && status.blackClearCycleSource == "manual")
    val transport by store.transport.collectAsStateWithLifecycle()
    val active by store.activeTransport.collectAsStateWithLifecycle()
    val shizukuState by store.shizuku.state.collectAsStateWithLifecycle()
    val rootState by store.root.state.collectAsStateWithLifecycle()
    val priority by store.priority.collectAsStateWithLifecycle()
    val dynamicColor by store.dynamicColor.collectAsStateWithLifecycle()
    val timeoutMs by store.ambientTimeoutMs.collectAsStateWithLifecycle()
    val quietEnabled by store.quietEnabled.collectAsStateWithLifecycle()
    val quietStart by store.quietStart.collectAsStateWithLifecycle()
    val quietEnd by store.quietEnd.collectAsStateWithLifecycle()
    val quietByDay by store.quietByDay.collectAsStateWithLifecycle()
    val quietDays by store.quietDays.collectAsStateWithLifecycle()
    val batteryGuard by store.batteryGuard.collectAsStateWithLifecycle()
    val batteryMinPct by store.batteryMinPct.collectAsStateWithLifecycle()
    val saverGuard by store.saverGuard.collectAsStateWithLifecycle()
    val suppression by store.suppression.collectAsStateWithLifecycle()
    val respectDnd by store.respectDnd.collectAsStateWithLifecycle()
    val quietDim by store.quietDim.collectAsStateWithLifecycle()
    val quietDimPct by store.quietDimPct.collectAsStateWithLifecycle()
    val screenOffOnly by store.screenOffOnly.collectAsStateWithLifecycle()
    val faceDownOnly by store.faceDownOnly.collectAsStateWithLifecycle()
    val faceDownNoticeAccepted by store.faceDownNoticeAccepted.collectAsStateWithLifecycle()
    val faceDownState by store.faceDownState.collectAsStateWithLifecycle()
    val faceDownSensorAvailable = remember(ctx) { ForegroundWatcher.hasFaceDownSensor(ctx) }
    val safetyGuardsDisabled by store.safetyGuardsDisabled.collectAsStateWithLifecycle()
    val glowSuppression = suppression?.takeIf {
        it.settingsSection() == SettingsSuppressionSection.GLOW
    }
    val pauseSuppression = suppression?.takeIf {
        it.settingsSection() == SettingsSuppressionSection.PAUSE
    }

    var checkingForUpdates by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var confirmingFaceDown by remember { mutableStateOf(false) }
    var confirmingSafetyLimits by remember { mutableStateOf(false) }
    val updateScope = rememberCoroutineScope()

    val checkForUpdates: () -> Unit = {
        checkingForUpdates = true
        updateScope.launch {
            updateResult = withContext(Dispatchers.IO) {
                GitHubUpdateChecker.check(BuildConfig.VERSION_NAME)
            }
            checkingForUpdates = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            store.shizuku.refresh()
            delay(1500)
        }
    }

    if (!safetyGuardsDisabled) {
        PixelCard(tone = 2) {
            SectionTitle(
                stringResource(R.string.setup_auto_off_title),
                trailing = { Caption(formatDuration(timeoutMs)) },
            )
            Caption(stringResource(R.string.setup_auto_off_body))
            Caption(stringResource(R.string.setup_auto_off_protection))
            GatedDurationSlider(
                label = stringResource(R.string.setup_stay_on_for),
                valueMs = timeoutMs,
                minMs = 5_000,
                safeMaxMs = Limits.WARN_ABOVE_MS,
                extendedMaxMs = Limits.AMBIENT_MAX_MS,
                unlockLabel = stringResource(R.string.setup_allow_five_minutes),
                warnFirst = stringResource(R.string.setup_warn_long_title) to
                    stringResource(R.string.setup_warn_long_body),
                warnSecond = stringResource(R.string.setup_warn_long_confirm_title) to
                    stringResource(R.string.setup_warn_long_confirm_body),
                onChange = { store.setAmbientTimeoutMs(it) },
            )
        }
    }

    PixelCard {
        SectionTitle(
            stringResource(R.string.setup_glow_conditions_title),
            trailing = {
                glowSuppression?.let { LivePill(stringResource(it.shortRes), ok = false) }
            },
        )
        ToggleRow(stringResource(R.string.setup_screen_off_only), screenOffOnly) {
            store.setScreenOffOnly(it)
        }
        ToggleRow(
            label = stringResource(R.string.setup_face_down_only),
            checked = faceDownOnly,
            // A restored setting still has to be switchable off on hardware without this sensor.
            enabled = faceDownSensorAvailable || faceDownOnly,
        ) { wanted ->
            when {
                !wanted -> store.setFaceDownOnly(false)
                faceDownNoticeAccepted -> store.setFaceDownOnly(true)
                else -> confirmingFaceDown = true
            }
        }
        Caption(stringResource(R.string.face_down_caution))
        if (!faceDownSensorAvailable) {
            Caption(stringResource(R.string.face_down_no_sensor))
        } else if (faceDownOnly) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Caption(stringResource(R.string.face_down_status_label))
                LivePill(
                    text = stringResource(
                        when (faceDownState) {
                            FaceDownState.INACTIVE -> R.string.face_down_state_inactive
                            FaceDownState.STARTING -> R.string.face_down_state_starting
                            FaceDownState.CHECKING -> R.string.face_down_state_checking
                            FaceDownState.FACE_DOWN -> R.string.face_down_state_face_down
                            FaceDownState.NOT_FACE_DOWN -> R.string.face_down_state_not_face_down
                            FaceDownState.UNAVAILABLE -> R.string.face_down_state_unavailable
                            FaceDownState.STALE -> R.string.face_down_state_stale
                            FaceDownState.START_FAILED -> R.string.face_down_state_start_failed
                        }
                    ),
                    ok = faceDownState == FaceDownState.FACE_DOWN,
                )
            }
        }
    }

    PixelCard {
        SectionTitle(
            stringResource(R.string.setup_pause_conditions_title),
            trailing = {
                pauseSuppression?.let { LivePill(stringResource(it.shortRes), ok = false) }
            },
        )
        ToggleRow(stringResource(R.string.suppression_quiet_hours), quietEnabled) {
            store.setQuietHours(it)
        }
        if (quietEnabled) {
            ToggleRow(stringResource(R.string.setup_quiet_by_day), quietByDay, onChange = store::setQuietByDay)
            if (quietByDay) {
                Caption(stringResource(R.string.setup_quiet_by_day_note))
                val dayNames = java.text.DateFormatSymbols.getInstance().weekdays
                quietDays.forEachIndexed { day, window ->
                    val calendarDay = (day + 1) % 7 + 1
                    ToggleRow(dayNames[calendarDay], window.enabled) {
                        store.setQuietDay(day, window.copy(enabled = it))
                    }
                    if (window.enabled) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(
                                onClick = { pickTime(ctx, window.startMin) { store.setQuietDay(day, window.copy(startMin = it)) } },
                                modifier = Modifier.weight(1f),
                            ) { ButtonLabel(stringResource(R.string.setup_quiet_from, clock(window.startMin))) }
                            FilledTonalButton(
                                onClick = { pickTime(ctx, window.endMin) { store.setQuietDay(day, window.copy(endMin = it)) } },
                                modifier = Modifier.weight(1f),
                            ) { ButtonLabel(stringResource(R.string.setup_quiet_until, clock(window.endMin))) }
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { pickTime(ctx, quietStart) { store.setQuietHours(true, startMin = it) } },
                        modifier = Modifier.weight(1f),
                    ) { ButtonLabel(stringResource(R.string.setup_quiet_from, clock(quietStart))) }
                    FilledTonalButton(
                        onClick = { pickTime(ctx, quietEnd) { store.setQuietHours(true, endMin = it) } },
                        modifier = Modifier.weight(1f),
                    ) { ButtonLabel(stringResource(R.string.setup_quiet_until, clock(quietEnd))) }
                }
            }
            ToggleRow(stringResource(R.string.setup_quiet_dim), quietDim) { store.setQuietDim(it) }
            if (quietDim) {
                PixelSlider(
                    stringResource(R.string.setup_dim_to),
                    quietDimPct.toFloat(),
                    2f..40f,
                    { store.setQuietDim(true, it.toInt()) },
                ) { stringResource(R.string.setup_percent, it.toInt()) }
            }
        }
        ToggleRow(stringResource(R.string.setup_respect_dnd), respectDnd) { store.setRespectDnd(it) }
        ToggleRow(stringResource(R.string.setup_pause_saver), saverGuard) { store.setSaverGuard(it) }
        ToggleRow(stringResource(R.string.setup_pause_low_battery), batteryGuard) {
            store.setBatteryGuard(it)
        }
        if (batteryGuard) {
            PixelSlider(
                stringResource(R.string.setup_pause_below),
                batteryMinPct.toFloat(),
                Limits.BATTERY_MIN_PCT.toFloat()..Limits.BATTERY_MAX_PCT.toFloat(),
                { store.setBatteryGuard(true, it.toInt()) },
            ) { stringResource(R.string.setup_percent, it.toInt()) }
            Caption(stringResource(R.string.setup_battery_note))
        }
    }

    PixelCard(tone = 2) {
        SectionTitle(stringResource(R.string.setup_safety_limits_title))
        ToggleRow(
            stringResource(R.string.setup_safety_limits_toggle),
            safetyGuardsDisabled,
        ) { wanted ->
            if (wanted) {
                confirmingSafetyLimits = true
            } else {
                store.setSafetyGuardsDisabled(false)
            }
        }
        Caption(stringResource(R.string.setup_safety_limits_caption))
    }

    if (confirmingFaceDown) {
        FaceDownConsentDialog(
            onAccepted = {
                store.acceptFaceDownNotice()
                store.setFaceDownOnly(true)
                confirmingFaceDown = false
            },
            onDismiss = { confirmingFaceDown = false },
        )
    }

    if (confirmingSafetyLimits) {
        AlertDialog(
            onDismissRequest = { confirmingSafetyLimits = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.setup_safety_limits_warn_title)) },
            text = {
                Text(
                    stringResource(R.string.setup_safety_limits_warn_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.setSafetyGuardsDisabled(true)
                        confirmingSafetyLimits = false
                    },
                ) { ButtonLabel(stringResource(R.string.setup_safety_limits_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSafetyLimits = false }) {
                    ButtonLabel(stringResource(R.string.setup_safety_limits_dismiss))
                }
            },
        )
    }

    var showConnectionDialog by remember { mutableStateOf(false) }
    if (showConnectionDialog) {
        ConnectionSetupDialog(store = store, onDismiss = { showConnectionDialog = false })
    }

    val rendererConnected = store.isRendererConnectedForUi(status)

    PixelCard(
        tone = 2,
        onClick = { showConnectionDialog = true },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionTitle(stringResource(R.string.setup_privileged_title))
                Caption(stringResource(R.string.setup_privileged_body))
            }
            Spacer(Modifier.width(12.dp))
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
    }



    PixelCard {
        SectionTitle(stringResource(R.string.setup_appearance_title))
        ToggleRow(stringResource(R.string.setup_wallpaper_colours), dynamicColor) {
            store.setDynamicColor(it)
        }
    }

    PixelCard {
        SectionTitle(
            stringResource(R.string.setup_updates_title),
            trailing = {
                Caption(
                    stringResource(
                        R.string.setup_updates_installed,
                        BuildConfig.VERSION_NAME,
                    )
                )
            },
        )
        when {
            checkingForUpdates -> Caption(stringResource(R.string.setup_updates_checking))
            updateResult == null -> Caption(stringResource(R.string.setup_updates_body))
            updateResult is UpdateCheckResult.Available -> Caption(
                stringResource(
                    R.string.setup_updates_available,
                    (updateResult as UpdateCheckResult.Available).release.versionName,
                )
            )
            updateResult is UpdateCheckResult.Current ->
                Caption(stringResource(R.string.setup_updates_current))
            updateResult is UpdateCheckResult.NoPublishedRelease ->
                Caption(stringResource(R.string.setup_updates_none))
            else -> Caption(stringResource(R.string.setup_updates_failed))
        }

        val available = updateResult as? UpdateCheckResult.Available
        if (available != null && !checkingForUpdates) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { openExternalUrl(ctx, available.release.pageUrl) }) {
                    ButtonLabel(stringResource(R.string.setup_updates_view_release))
                }
                TextButton(onClick = checkForUpdates) {
                    ButtonLabel(stringResource(R.string.setup_updates_check_again))
                }
            }
        } else {
            FilledTonalButton(
                onClick = checkForUpdates,
                enabled = !checkingForUpdates,
            ) {
                ButtonLabel(
                    stringResource(
                        if (checkingForUpdates) R.string.setup_updates_checking
                        else R.string.setup_updates_check,
                    )
                )
            }
        }
    }



    PixelCard(tone = 2) {
        SectionTitle(stringResource(R.string.setup_led_diagnostics_title))
        Caption(stringResource(R.string.setup_led_diagnostics_body))
        FilledTonalButton(
            onClick = {
                updateScope.launch {
                    val payload = withContext(Dispatchers.IO) {
                        val snapshot = store.freshRendererStatusSnapshot()
                        RendererDiagnostics.format(
                            status = snapshot.status,
                            selectedTransport = snapshot.selectedTransport,
                            activeTransport = snapshot.activeTransport,
                            appVersionName = BuildConfig.VERSION_NAME,
                            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
                            deviceModel = Build.MODEL,
                            buildId = Build.ID,
                            sdkInt = Build.VERSION.SDK_INT,
                            capturedAtEpochMs = snapshot.capturedAtEpochMs,
                        )
                    }
                    copy(ctx, payload, R.string.setup_led_diagnostics_copied)
                }
            },
        ) {
            ButtonLabel(stringResource(R.string.setup_led_diagnostics_copy))
        }
        Caption(stringResource(R.string.setup_led_cleanup_retry_body))
        if (!status.alive || status.rendererStale) {
            Caption(stringResource(R.string.setup_led_cleanup_renderer_unavailable))
        }
        FilledTonalButton(
            onClick = { store.retryLedCleanup() },
            enabled = store.isLedCleanupRetryEnabled(
                masterEnabled = masterEnabled,
                status = status,
                requestPending = manualCleanupPending,
            ),
        ) {
            ButtonLabel(
                stringResource(
                    if (manualCleanupInProgress) R.string.setup_led_cleanup_retry_pending
                    else R.string.setup_led_cleanup_retry,
                )
            )
        }
    }

    PixelCard {
        SectionTitle(stringResource(R.string.setup_priority_title))
        Caption(stringResource(R.string.setup_priority_body))
        PixelSlider(
            stringResource(R.string.setup_priority_label),
            priority.toFloat(),
            -10f..10f,
            { store.setPriority(it.toInt()) },
        ) { it.toInt().toString() }
    }


}



// The confirmation is a resource rather than a string because these run from a click, outside
// composition. "hilight" is the clipboard's own label for the clip, not something a reader sees.
internal fun copy(ctx: Context, text: String, @StringRes toast: Int) {
    ctx.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("hilight", text))
    Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
}

internal fun share(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.adb_share_title)))
}

internal fun openShizuku(ctx: Context) {
    val launch = ctx.packageManager.getLaunchIntentForPackage(ShizukuBackend.SHIZUKU_PKG)
    if (launch != null) ctx.startActivity(launch) else openShizukuListing(ctx)
}

internal fun openShizukuListing(ctx: Context) {
    val uri = Uri.parse("https://shizuku.rikka.app/")
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure { Toast.makeText(ctx, R.string.setup_no_browser, Toast.LENGTH_SHORT).show() }
}

private fun openExternalUrl(ctx: Context, pageUrl: String) {
    val uri = Uri.parse(pageUrl)
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure { Toast.makeText(ctx, R.string.setup_no_browser, Toast.LENGTH_SHORT).show() }
}

internal fun postSelfTestNotification(ctx: Context) {
    val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
    // The channel id stays a literal — it is a key, not a label. The channel *name* is a label: it
    // appears in the system's own notification settings for this app.
    nm.createNotificationChannel(
        android.app.NotificationChannel(
            "selftest",
            ctx.getString(R.string.setup_selftest_channel),
            android.app.NotificationManager.IMPORTANCE_DEFAULT,
        )
    )
    nm.notify(
        42,
        android.app.Notification.Builder(ctx, "selftest")
            .setContentTitle(ctx.getString(R.string.setup_selftest_title))
            .setContentText(ctx.getString(R.string.setup_selftest_body))
            .setSmallIcon(R.drawable.beacon_logo)
            .setAutoCancel(true)
            .build()
    )
}

/** minutes since midnight to a 24-hour clock string */
fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun pickTime(ctx: Context, currentMinutes: Int, onPicked: (Int) -> Unit) {
    android.app.TimePickerDialog(
        ctx,
        { _, hour, minute -> onPicked(hour * 60 + minute) },
        currentMinutes / 60,
        currentMinutes % 60,
        android.text.format.DateFormat.is24HourFormat(ctx),
    ).show()
}

internal fun hasNotificationAccess(ctx: Context): Boolean {
    val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        ?: return false
    return flat.contains(ctx.packageName)
}
