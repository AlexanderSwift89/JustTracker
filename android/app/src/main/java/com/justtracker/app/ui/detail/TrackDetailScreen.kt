package com.justtracker.app.ui.detail

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justtracker.app.R
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.ui.common.ActivityBadge
import com.justtracker.app.ui.common.SpeedLegend
import com.justtracker.app.ui.common.StatTile
import com.justtracker.app.ui.common.TrackCursor
import com.justtracker.app.ui.common.TrackMap
import com.justtracker.app.ui.common.appViewModel
import com.justtracker.app.ui.history.DeleteDialog
import com.justtracker.app.ui.history.RenameDialog
import com.justtracker.app.ui.poi.PoiCard
import com.justtracker.app.ui.poi.PoiCardViewModel
import com.justtracker.app.ui.theme.ActivityColors
import com.justtracker.app.util.TimeFormat
import com.justtracker.app.util.UnitFormatter
import com.justtracker.app.util.labelRes
import kotlinx.coroutines.launch

/**
 * Scrubber under the detail map (docs/04_ux_design.md §2.9): slider over the track distance with
 * "elapsed · %" caption, arrows to step one point, and the values at the cursor.
 */
@Composable
private fun TrackCursorPanel(
    cursor: TrackCursor,
    formatter: UnitFormatter,
    onFraction: (Float) -> Unit,
    onStep: (Int) -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onStep(-1) }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = stringResource(R.string.detail_cursor_prev))
            }
            Slider(
                value = cursor.fraction,
                onValueChange = onFraction,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onStep(1) }) {
                Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.detail_cursor_next))
            }
        }
        Text(
            stringResource(R.string.detail_cursor_caption, TimeFormat.duration(cursor.elapsedMs), (cursor.fraction * 100).toInt()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            CursorValue(stringResource(R.string.detail_cursor_speed), formatter.speed(cursor.speedMps.toDouble()))
            CursorValue(stringResource(R.string.detail_distance), formatter.distance(cursor.distanceFromStartM))
            CursorValue(stringResource(R.string.detail_cursor_time), TimeFormat.timeOfDay(cursor.timestamp))
            CursorValue(stringResource(R.string.detail_cursor_altitude), cursor.altitudeM?.let { formatter.elevation(it) } ?: "—")
        }
    }
}

@Composable
private fun CursorValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(
    trackId: Long,
    onBack: () -> Unit,
    viewModel: TrackDetailViewModel = appViewModel(key = "detail-$trackId") { TrackDetailViewModel(it, trackId) },
    poiCardViewModel: PoiCardViewModel = appViewModel(key = "poi-detail-$trackId") { PoiCardViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val poiCard by poiCardViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val formatter = remember(state.units) { UnitFormatter(context, state.units) }
    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showTypeSheet by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val exportFailed = stringResource(R.string.detail_export_failed)
    val chooserTitle = stringResource(R.string.detail_export_chooser)

    val track = state.track
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text(track?.name ?: stringResource(R.string.detail_title), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = {
                            menuOpen = false
                            showRename = true
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_change_type)) }, onClick = {
                            menuOpen = false
                            showTypeSheet = true
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_export_gpx)) }, onClick = {
                            menuOpen = false
                            scope.launch {
                                val intent = viewModel.buildShareIntent(context)
                                if (intent == null) {
                                    snackbar.showSnackbar(exportFailed)
                                } else {
                                    context.startActivity(Intent.createChooser(intent, chooserTitle))
                                }
                            }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = {
                            menuOpen = false
                            showDelete = true
                        })
                    }
                },
            )
        },
    ) { padding ->
        if (track == null) {
            if (state.loaded) {
                Text(
                    stringResource(R.string.detail_not_found),
                    modifier = Modifier
                        .padding(padding)
                        .padding(24.dp),
                )
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(0.45f),
            ) {
                TrackMap(
                    segments = state.segments,
                    modifier = Modifier.fillMaxSize(),
                    lineColor = ActivityColors.of(track.activityType),
                    maxSpeedMps = track.maxSpeedMps,
                    highlight = state.cursor?.point,
                    fitToTrack = true,
                    showStartFinish = true,
                    onTrackTap = viewModel::onTrackTap,
                    pois = state.pois,
                    onPoiClick = { poi -> poiCardViewModel.open(poi, distanceM = null) },
                )
                SpeedLegend(
                    maxSpeedMps = track.maxSpeedMps,
                    formatter = formatter,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                )
            }
            state.cursor?.let { cursor ->
                TrackCursorPanel(
                    cursor = cursor,
                    formatter = formatter,
                    onFraction = viewModel::scrubToFraction,
                    onStep = viewModel::stepCursor,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActivityBadge(track.activityType, size = 32)
                Text(
                    stringResource(track.activityType.labelRes()),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clickable { showTypeSheet = true },
                )
            }
            val pace = if (track.activityType == ActivityType.WALK || track.activityType == ActivityType.RUN) {
                formatter.pace(if (track.distanceM > 0) (track.movingTimeMs / 1000.0) / track.distanceM else null)
            } else {
                null
            }
            // Recording time (Start → Stop) is the primary time; moving time is secondary (US-08).
            val tiles = buildList {
                add(stringResource(R.string.detail_distance) to formatter.distance(track.distanceM))
                add(stringResource(R.string.detail_recording_time) to TimeFormat.duration(track.recordingTimeMs()))
                add(stringResource(R.string.detail_moving_time) to TimeFormat.duration(track.movingTimeMs))
                add(stringResource(R.string.detail_avg_speed) to formatter.speed(track.avgSpeedMps))
                add(stringResource(R.string.detail_max_speed) to formatter.speed(track.maxSpeedMps))
                add(stringResource(R.string.detail_elevation_gain) to formatter.elevation(track.elevationGainM, signed = true))
                add(stringResource(R.string.detail_elevation_loss) to formatter.elevation(-track.elevationLossM, signed = true))
                if (pace != null) add(stringResource(R.string.detail_pace) to pace)
                add(stringResource(R.string.detail_points) to track.pointCount.toString())
                if (track.pausedTimeMs > 0) add(stringResource(R.string.detail_paused_time) to TimeFormat.duration(track.pausedTimeMs))
                add(stringResource(R.string.detail_started) to TimeFormat.dateTime(track.startedAt))
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.55f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tiles) { (label, value) -> StatTile(label = label, value = value) }
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
    if (showRename && track != null) {
        RenameDialog(initial = track.name, onDismiss = { showRename = false }, onSave = {
            viewModel.rename(it)
            showRename = false
        })
    }
    if (showDelete) {
        DeleteDialog(onDismiss = { showDelete = false }, onConfirm = {
            showDelete = false
            viewModel.delete(onDone = onBack)
        })
    }
    if (showTypeSheet && track != null) {
        ModalBottomSheet(onDismissRequest = { showTypeSheet = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    stringResource(R.string.detail_type_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                if (!track.activityManual) {
                    Text(
                        stringResource(R.string.detail_type_auto_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
                listOf(ActivityType.WALK, ActivityType.RUN, ActivityType.BIKE, ActivityType.CAR, ActivityType.OTHER).forEach { type ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setActivityType(type)
                                showTypeSheet = false
                            }
                            .padding(horizontal = 24.dp)
                            .height(56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ActivityBadge(type, size = 32)
                        Text(
                            stringResource(type.labelRes()),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .padding(start = 16.dp)
                                .weight(1f),
                        )
                        if (type == track.activityType) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
