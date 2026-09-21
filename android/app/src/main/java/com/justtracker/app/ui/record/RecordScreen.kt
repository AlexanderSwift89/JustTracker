package com.justtracker.app.ui.record

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justtracker.app.R
import com.justtracker.app.ui.common.ActivityBadge
import com.justtracker.app.ui.common.Permissions
import com.justtracker.app.ui.common.LocalAppContainer
import com.justtracker.app.ui.common.MapModeBadge
import com.justtracker.app.ui.common.SpeedLegend
import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.ui.common.TrackMap
import com.justtracker.app.ui.common.TrackTapCard
import com.justtracker.app.ui.common.appViewModel
import com.justtracker.app.domain.poi.PoiProximity
import com.justtracker.app.ui.poi.PoiCard
import com.justtracker.app.ui.poi.PoiCardViewModel
import com.justtracker.app.util.TimeFormat
import com.justtracker.app.ui.theme.tabular
import com.justtracker.app.ui.theme.topOnly
import com.justtracker.app.util.UnitFormatter

@Composable
fun RecordScreen(
    onTrackFinished: (Long) -> Unit,
    viewModel: RecordViewModel = appViewModel { RecordViewModel(it) },
    poiCardViewModel: PoiCardViewModel = appViewModel(key = "poi-record") { PoiCardViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appSettings by LocalAppContainer.current.settingsFlow.collectAsStateWithLifecycle()
    val poiCard by poiCardViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    val formatter = remember(state.units) { UnitFormatter(context, state.units) }

    var hasPermission by remember { mutableStateOf(Permissions.hasLocation(context)) }
    var locationEnabled by remember { mutableStateOf(Permissions.isLocationEnabled(context)) }
    var deniedForever by remember { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var statsExpanded by rememberSaveable { mutableStateOf(false) }
    var showStopDialog by remember { mutableStateOf(false) }
    var showGpsOffDialog by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    var handledFinishSerial by rememberSaveable { mutableIntStateOf(state.live.finishSerial) }

    val tooFewMessage = stringResource(R.string.record_too_few_points)

    // Re-check permission / location toggle whenever the user returns from system settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = Permissions.hasLocation(context)
                locationEnabled = Permissions.isLocationEnabled(context)
                if (hasPermission) deniedForever = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Keep screen on while recording when the setting is enabled.
    DisposableEffect(state.keepScreenOn, state.status) {
        val window = activity?.window
        val on = state.keepScreenOn && state.status == RecordStatus.RECORDING
        if (on) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(Unit) { if (hasPermission) viewModel.seedLastKnownLocation() }

    // Keep the distance in the open place card in sync with the live position.
    LaunchedEffect(state.position, poiCard?.poi?.id) {
        val card = poiCard ?: return@LaunchedEffect
        val pos = state.position
        poiCardViewModel.updateDistance(pos?.let { PoiProximity.distanceTo(card.poi, it.latitude, it.longitude) })
    }

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
                segments = state.segments,
                modifier = Modifier.fillMaxSize(),
                maxSpeedMps = state.track?.maxSpeedMps ?: 0.0,
                position = state.position,
                highlight = state.tapped?.point,
                follow = follow,
                onUserGesture = { follow = false },
                onTrackTap = viewModel::onTrackTap,
                pois = state.pois,
                onPoiClick = { poi ->
                    val pos = state.position
                    poiCardViewModel.open(poi, pos?.let { PoiProximity.distanceTo(poi, it.latitude, it.longitude) })
                },
            )

            AnimatedVisibility(
                visible = !locationEnabled,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
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
                if (state.status != RecordStatus.IDLE && state.segments.isNotEmpty()) {
                    SpeedLegend(maxSpeedMps = state.track?.maxSpeedMps ?: 0.0, formatter = formatter)
                }
                if (appSettings.mapMode == MapMode.OFFLINE) MapModeBadge()
            }

            // Bottom panel behaves like an M3 standard bottom sheet: edge to edge, only the top
            // corners rounded, no outer margins — so it covers as little map as possible.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
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

    poiCard?.let { card ->
        PoiCard(
            state = card,
            formatter = formatter,
            onDismiss = poiCardViewModel::close,
            onToggleSpeak = poiCardViewModel::toggleSpeak,
        )
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

@Composable
private fun GpsOffBanner(onEnable: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.record_gps_off_banner),
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEnable) { Text(stringResource(R.string.record_gps_off_action)) }
        }
    }
}

@Composable
private fun PermissionPanel(deniedForever: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    Text(stringResource(R.string.record_permission_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(if (deniedForever) R.string.record_permission_denied_forever else R.string.record_permission_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
    )
    if (deniedForever) {
        Button(onClick = onOpenSettings) { Text(stringResource(R.string.action_open_settings)) }
    } else {
        Button(onClick = onAllow) { Text(stringResource(R.string.action_allow)) }
    }
}

@Composable
private fun IdlePanel(onStart: () -> Unit) {
    Text(stringResource(R.string.record_ready), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    FilledIconButton(
        onClick = onStart,
        modifier = Modifier.size(72.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Icon(Icons.Filled.FiberManualRecord, contentDescription = stringResource(R.string.record_start), modifier = Modifier.size(36.dp))
    }
    Text(stringResource(R.string.record_start), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
}

/**
 * Compact live HUD (~110 dp): speed on top, distance + recording time below, pause/stop stacked on
 * the right. Every number carries its unit and a text label, so nothing needs to be guessed from
 * position alone. Tapping the panel reveals the secondary row (avg / max / moving time).
 */
@Composable
private fun RecordingPanel(
    state: RecordUiState,
    formatter: UnitFormatter,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val track = state.track ?: return
    val paused = state.status == RecordStatus.PAUSED
    val speedText = if (state.gpsSearching || paused) stringResource(R.string.record_speed_unavailable) else formatter.speedValue(state.live.currentSpeedMps.toDouble())
    val contentAlpha = if (paused) 0.6f else 1f
    val (distanceValue, distanceUnit) = formatter.distanceParts(track.distanceM)
    val recordingText = TimeFormat.durationClock(state.recordingTimeMs)
    val movingText = TimeFormat.durationClock(track.movingTimeMs)
    val durationUnit = formatter.durationUnit()
    val speedLabel = stringResource(R.string.record_label_speed)
    val distanceLabel = stringResource(R.string.record_label_distance)
    val recordingLabel = stringResource(R.string.record_label_recording_time)
    val summary = "$speedLabel $speedText ${formatter.speedUnit()}, $distanceLabel $distanceValue $distanceUnit, $recordingLabel $recordingText"
    val toggleLabel = stringResource(if (expanded) R.string.record_collapse_stats else R.string.record_expand_stats)

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = toggleLabel, onClick = onToggleExpanded),
    ) {
        // Drag-handle-like affordance for the expandable secondary row.
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(18.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = summary },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        speedText,
                        style = MaterialTheme.typography.headlineLarge.tabular(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                        maxLines = 1,
                    )
                    UnitText(formatter.speedUnit(), style = MaterialTheme.typography.titleSmall, bottomPadding = 5.dp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetricLabel(speedLabel)
                    Spacer(Modifier.width(8.dp))
                    if (paused) {
                        Text(
                            stringResource(R.string.record_paused),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        GpsIndicator(searching = state.gpsSearching)
                    }
                    if (track.activityType != com.justtracker.app.domain.model.ActivityType.UNKNOWN) {
                        Spacer(Modifier.width(8.dp))
                        ActivityBadge(track.activityType, size = 20)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    MetricCell(distanceValue, distanceUnit, distanceLabel, contentAlpha, Modifier.weight(1f))
                    // Recording time = Start → Stop including pauses; moving time lives in the expanded row.
                    MetricCell(recordingText, durationUnit, recordingLabel, contentAlpha, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedContent(targetState = paused, label = "pauseResume") { isPaused ->
                    if (isPaused) {
                        FilledIconButton(onClick = onResume, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.record_resume))
                        }
                    } else {
                        FilledTonalIconButton(onClick = onPause, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.record_pause))
                        }
                    }
                }
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.record_stop))
                }
            }
        }
        AnimatedVisibility(visible = expanded) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            ) {
                MetricCell(formatter.speedValue(track.avgSpeedMps), formatter.speedUnit(), stringResource(R.string.record_label_avg_speed), contentAlpha, Modifier.weight(1f), compact = true)
                MetricCell(formatter.speedValue(track.maxSpeedMps), formatter.speedUnit(), stringResource(R.string.record_label_max_speed), contentAlpha, Modifier.weight(1f), compact = true)
                MetricCell(movingText, durationUnit, stringResource(R.string.record_label_moving_time), contentAlpha, Modifier.weight(1.3f), compact = true)
            }
        }
    }
}

@Composable
private fun GpsIndicator(searching: Boolean) {
    val color = if (searching) MaterialTheme.colorScheme.outline else Color(0xFF2E7D32)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (searching) Icons.Filled.GpsNotFixed else Icons.Filled.GpsFixed,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Text(
            stringResource(if (searching) R.string.record_gps_searching else R.string.record_gps_ok),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** Value + unit on one line, text label underneath: "1,25 km / Distance". */
@Composable
private fun MetricCell(
    value: String,
    unit: String,
    label: String,
    contentAlpha: Float,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = (if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge).tabular(),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                maxLines = 1,
            )
            UnitText(unit, style = MaterialTheme.typography.labelMedium, bottomPadding = if (compact) 2.dp else 3.dp)
        }
        MetricLabel(label)
    }
}

@Composable
private fun UnitText(unit: String, style: androidx.compose.ui.text.TextStyle, bottomPadding: androidx.compose.ui.unit.Dp) {
    Text(
        unit,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.padding(start = 4.dp, bottom = bottomPadding),
    )
}

@Composable
private fun MetricLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

