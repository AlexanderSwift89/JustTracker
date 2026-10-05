package com.justtracker.app.ui.record

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justtracker.app.R
import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.ui.common.MapModeBadge
import com.justtracker.app.ui.common.SpeedLegend
import com.justtracker.app.ui.common.TrackMap
import com.justtracker.app.ui.common.TrackTapCard
import com.justtracker.app.ui.common.appViewModel
import com.justtracker.app.ui.theme.topOnly
import com.justtracker.app.util.Permissions
import com.justtracker.app.util.UnitFormatter

@Composable
fun RecordScreen(
    onTrackFinished: (Long) -> Unit,
    viewModel: RecordViewModel = appViewModel { c ->
        RecordViewModel(c.trackRepository, c.settingsRepository, c.trackingController, c.locationSource, c.dispatchers)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val snackbar = remember { SnackbarHostState() }
    val formatter = remember(state.units) { UnitFormatter(context, state.units) }

    var hasPermission by remember { mutableStateOf(Permissions.hasLocation(context)) }
    var locationEnabled by remember { mutableStateOf(Permissions.isLocationEnabled(context)) }
    var deniedForever by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var statsExpanded by rememberSaveable { mutableStateOf(false) }
    // Dialogs and a start waiting for a permission answer survive an activity recreation too.
    var showStopDialog by rememberSaveable { mutableStateOf(false) }
    var showGpsOffDialog by rememberSaveable { mutableStateOf(false) }
    var pendingStart by rememberSaveable { mutableStateOf(false) }
    var handledFinishSerial by rememberSaveable { mutableIntStateOf(state.live.finishSerial) }

    val tooFewMessage = stringResource(R.string.record_too_few_points)

    // Re-check permission / location toggle whenever the user returns from system settings.
    LifecycleResumeEffect(Unit) {
        hasPermission = Permissions.hasLocation(context)
        locationEnabled = Permissions.isLocationEnabled(context)
        if (hasPermission) deniedForever = false
        onPauseOrDispose { }
    }

    // Keep screen on while recording when the setting is enabled.
    DisposableEffect(state.keepScreenOn, state.status) {
        val window = activity?.window
        val on = state.keepScreenOn && state.status == RecordStatus.RECORDING
        if (on) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(Unit) { if (hasPermission) viewModel.seedLastKnownLocation() }

    // React exactly once per finish event: open the detail screen or explain why nothing was saved.
    LaunchedEffect(state.live.finishSerial) {
        if (state.live.finishSerial != handledFinishSerial) {
            handledFinishSerial = state.live.finishSerial
            val id = state.live.lastFinishedTrackId
            if (id != null && !state.live.lastFinishDiscarded) onTrackFinished(id) else snackbar.showSnackbar(tooFewMessage)
        }
    }

    fun tryStart() {
        if (!Permissions.isLocationEnabled(context)) {
            locationEnabled = false
            showGpsOffDialog = true
            return
        }
        follow = true
        viewModel.start()
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingStart) {
            pendingStart = false
            tryStart()
        }
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasPermission = result[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasPermission) {
            viewModel.seedLastKnownLocation()
            if (Permissions.needsNotificationPermission() && !Permissions.hasNotifications(context)) {
                notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else if (pendingStart) {
                pendingStart = false
                tryStart()
            }
        } else {
            pendingStart = false
            deniedForever = activity?.let { Permissions.locationDeniedForever(it) } ?: false
        }
    }

    fun onStartClick() {
        if (hasPermission) {
            if (Permissions.needsNotificationPermission() && !Permissions.hasNotifications(context)) {
                pendingStart = true
                notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                tryStart()
            }
        } else {
            pendingStart = true
            locationLauncher.launch(Permissions.LOCATION)
        }
    }

    // The map is drawn edge to edge; every overlay applies the safe-drawing insets itself. The bottom
    // inset is already consumed by the NavigationSuiteScaffold when the bar is shown, so the panel only
    // gains extra padding in rail/landscape mode.
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TrackMap(
                line = state.line,
                modifier = Modifier.fillMaxSize(),
                maxSpeedMps = state.track?.maxSpeedMps ?: 0.0,
                position = state.position,
                highlight = state.tapped?.point,
                follow = follow,
                onUserGesture = { follow = false },
                onTrackTap = viewModel::onTrackTap,
            )

            AnimatedVisibility(
                visible = !locationEnabled,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .widthIn(max = PANEL_MAX_WIDTH),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                GpsOffBanner(onEnable = { Permissions.openLocationSettings(context) })
            }

            AnimatedVisibility(
                visible = !follow,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(16.dp),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                SmallFloatingActionButton(onClick = { follow = true }) {
                    Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.record_center_map))
                }
            }

            // Top-start overlays: speed legend (US-15, once there is a line) and the offline-map cue (US-22).
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.status != RecordStatus.IDLE && !state.line.isEmpty) {
                    SpeedLegend(maxSpeedMps = state.track?.maxSpeedMps ?: 0.0, formatter = formatter)
                }
                if (state.mapMode == MapMode.OFFLINE) MapModeBadge()
            }

            // Bottom panel behaves like an M3 standard bottom sheet: edge to edge, only the top corners
            // rounded, no outer margins — so it covers as little map as possible. In a wide window (a phone
            // in landscape) it is capped at the sheet's max width and centred, leaving the map on both sides.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .widthIn(max = PANEL_MAX_WIDTH)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                state.tapped?.let { tap ->
                    TrackTapCard(
                        info = tap,
                        formatter = formatter,
                        onDismiss = viewModel::dismissTap,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge.topOnly(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                ) {
                    Column(
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when {
                            !hasPermission -> PermissionPanel(
                                deniedForever = deniedForever,
                                onAllow = { locationLauncher.launch(Permissions.LOCATION) },
                                onOpenSettings = { Permissions.openAppSettings(context) },
                            )
                            state.status == RecordStatus.IDLE -> IdlePanel(onStart = ::onStartClick)
                            else -> RecordingPanel(
                                state = state,
                                formatter = formatter,
                                expanded = statsExpanded,
                                onToggleExpanded = { statsExpanded = !statsExpanded },
                                onToggleAcceleration = viewModel::toggleAcceleration,
                                onAccelerationHintShown = viewModel::onAccelerationHintShown,
                                onPause = viewModel::pause,
                                onResume = viewModel::resume,
                                onStop = { showStopDialog = true },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text(stringResource(R.string.record_stop_dialog_title)) },
            text = { Text(stringResource(R.string.record_stop_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showStopDialog = false
                    viewModel.stop()
                }) { Text(stringResource(R.string.record_stop_dialog_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showStopDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (showGpsOffDialog) {
        AlertDialog(
            onDismissRequest = { showGpsOffDialog = false },
            title = { Text(stringResource(R.string.record_gps_off_banner)) },
            confirmButton = {
                TextButton(onClick = {
                    showGpsOffDialog = false
                    Permissions.openLocationSettings(context)
                }) { Text(stringResource(R.string.action_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { showGpsOffDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (state.needsRecovery && hasPermission) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.record_recovered_title)) },
            text = { Text(stringResource(R.string.record_recovered_body)) },
            confirmButton = {
                TextButton(onClick = {
                    follow = true
                    viewModel.recover()
                }) { Text(stringResource(R.string.record_recovered_continue)) }
            },
            dismissButton = { TextButton(onClick = viewModel::stop) { Text(stringResource(R.string.record_recovered_finish)) } },
        )
    }
}

/** M3 bottom sheet max width: the panel and the GPS banner do not stretch across a landscape screen. */
private val PANEL_MAX_WIDTH = 640.dp
