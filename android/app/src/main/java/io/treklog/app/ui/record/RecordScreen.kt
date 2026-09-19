package io.treklog.app.ui.record

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.treklog.app.R
import io.treklog.app.ui.common.ActivityBadge
import io.treklog.app.ui.common.Permissions
import io.treklog.app.ui.common.TrackMap
import io.treklog.app.ui.common.appViewModel
import io.treklog.app.domain.poi.PoiProximity
import io.treklog.app.ui.poi.PoiCard
import io.treklog.app.ui.poi.PoiCardViewModel
import io.treklog.app.util.TimeFormat
import io.treklog.app.util.UnitFormatter

@Composable
fun RecordScreen(
    onTrackFinished: (Long) -> Unit,
    viewModel: RecordViewModel = appViewModel { RecordViewModel(it) },
    poiCardViewModel: PoiCardViewModel = appViewModel(key = "poi-record") { PoiCardViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TrackMap(
                segments = state.segments,
                modifier = Modifier.fillMaxSize(),
                position = state.position,
                follow = follow,
                onUserGesture = { follow = false },
                pois = state.pois,
                onPoiClick = { poi ->
                    val pos = state.position
                    poiCardViewModel.open(poi, pos?.let { PoiProximity.distanceTo(poi, it.latitude, it.longitude) })
                },
            )

            AnimatedVisibility(
                visible = !locationEnabled,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                GpsOffBanner(onEnable = { Permissions.openLocationSettings(context) })
            }

            AnimatedVisibility(
                visible = !follow,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                SmallFloatingActionButton(onClick = { follow = true }) {
                    Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.record_center_map))
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
            ) {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
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
                            onPause = viewModel::pause,
                            onResume = viewModel::resume,
                            onStop = { showStopDialog = true },
                        )
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
        shape = RoundedCornerShape(12.dp),
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

@Composable
private fun RecordingPanel(
    state: RecordUiState,
    formatter: UnitFormatter,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val track = state.track ?: return
    val paused = state.status == RecordStatus.PAUSED
    val speedText = if (state.gpsSearching || paused) stringResource(R.string.record_speed_unavailable) else formatter.speedValue(state.live.currentSpeedMps.toDouble())
    val contentAlpha = if (paused) 0.6f else 1f
    val distanceText = formatter.distance(track.distanceM)
    val movingText = TimeFormat.duration(track.movingTimeMs)
    val avgText = formatter.speed(track.avgSpeedMps)
    val summary = "$speedText ${formatter.speedUnit()}, $distanceText, $movingText"

    Column(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = summary },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                speedText,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.padding(bottom = 10.dp)) {
                Text(formatter.speedUnit(), style = MaterialTheme.typography.titleMedium)
                GpsIndicator(searching = state.gpsSearching, paused = paused)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Metric(stringResource(R.string.record_label_distance), distanceText)
            Metric(stringResource(R.string.record_label_moving_time), movingText)
            Metric(stringResource(R.string.record_label_avg_speed), avgText)
        }
        if (paused) {
            Text(
                stringResource(R.string.record_paused),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        AnimatedContent(targetState = paused, label = "buttons") { isPaused ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isPaused) {
                    FilledIconButton(onClick = onResume, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.record_resume), modifier = Modifier.size(32.dp))
                    }
                } else {
                    FilledTonalIconButton(onClick = onPause, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.record_pause), modifier = Modifier.size(32.dp))
                    }
                }
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.record_stop), modifier = Modifier.size(32.dp))
                }
            }
        }
        if (track.activityType != io.treklog.app.domain.model.ActivityType.UNKNOWN) {
            Spacer(Modifier.height(8.dp))
            ActivityBadge(track.activityType, size = 28)
        }
    }
}

@Composable
private fun GpsIndicator(searching: Boolean, paused: Boolean) {
    val color = if (searching || paused) MaterialTheme.colorScheme.outline else Color(0xFF2E7D32)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (searching) Icons.Filled.GpsNotFixed else Icons.Filled.GpsFixed,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Text(
            stringResource(if (searching && !paused) R.string.record_gps_searching else R.string.record_gps_ok),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
