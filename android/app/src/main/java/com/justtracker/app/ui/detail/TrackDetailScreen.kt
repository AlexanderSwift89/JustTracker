package com.justtracker.app.ui.detail

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.justtracker.app.ui.common.LocalAppContainer
import com.justtracker.app.ui.common.MapModeBadge
import com.justtracker.app.ui.common.SkeletonBox
import com.justtracker.app.ui.common.SkeletonGroup
import com.justtracker.app.ui.common.SkeletonLine
import com.justtracker.app.ui.common.SkeletonStatTile
import com.justtracker.app.ui.common.SpeedLegend
import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.ui.common.StatTile
import com.justtracker.app.ui.common.TrackCursor
import com.justtracker.app.ui.common.TrackMap
import com.justtracker.app.ui.common.appViewModel
import com.justtracker.app.ui.common.rememberSkeletonVisible
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
    val appSettings by LocalAppContainer.current.settingsFlow.collectAsStateWithLifecycle()
    val poiCard by poiCardViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val formatter = remember(state.units) { UnitFormatter(context, state.units) }
    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showTypeSheet by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    // Stats grid is shown by default; the user can fold it away to give the map the whole screen while the
    // scrubber and its values stay put (docs/04_ux_design.md §2.4). Survives rotation, not navigation.
    var statsVisible by rememberSaveable { mutableStateOf(true) }
    val mapWeight by animateFloatAsState(if (statsVisible) MAP_WEIGHT else 1f, tween(STATS_TOGGLE_MS), label = "mapWeight")
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
        val skeleton = rememberSkeletonVisible(!state.loaded)
        if (track == null) {
            when {
                state.loaded -> Text(
                    stringResource(R.string.detail_not_found),
                    modifier = Modifier
                        .padding(padding)
                        .padding(24.dp),
                )
                skeleton -> TrackDetailSkeleton(Modifier.padding(padding))
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
                    .weight(mapWeight),
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
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SpeedLegend(maxSpeedMps = track.maxSpeedMps, formatter = formatter)
                    if (appSettings.mapMode == MapMode.OFFLINE) MapModeBadge()
                }
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
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActivityBadge(track.activityType, size = 32)
                Text(
                    stringResource(track.activityType.labelRes()),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .weight(1f)
                        .clickable { showTypeSheet = true },
                )
                IconButton(onClick = { statsVisible = !statsVisible }) {
                    Icon(
                        if (statsVisible) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(if (statsVisible) R.string.detail_hide_stats else R.string.detail_show_stats),
                    )
                }
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
            // The grid takes what the map gives up; it leaves the composition once the map owns the whole height.
            if (mapWeight < 1f) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f - mapWeight),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tiles) { (label, value) -> StatTile(label = label, value = value) }
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

/**
 * Placeholder with the screen's own layout while the track and its points are read from Room
 * (docs/04_ux_design.md §7): map area, scrubber row, activity row and the first tiles of the grid.
 */
@Composable
private fun TrackDetailSkeleton(modifier: Modifier = Modifier) {
    SkeletonGroup(modifier) {
        Column(Modifier.fillMaxSize()) {
            SkeletonBox(
                Modifier
                    .fillMaxWidth()
                    .weight(MAP_WEIGHT),
                shape = MaterialTheme.shapes.extraSmall,
            )
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkeletonBox(Modifier.size(24.dp), shape = MaterialTheme.shapes.small)
                    SkeletonBox(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp)
                            .height(8.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                    SkeletonBox(Modifier.size(24.dp), shape = MaterialTheme.shapes.small)
                }
                SkeletonLine(
                    width = 140.dp,
                    height = 14.dp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 10.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    repeat(4) { SkeletonLine(width = 56.dp, height = 18.dp) }
                }
            }
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBox(Modifier.size(32.dp), shape = androidx.compose.foundation.shape.CircleShape)
                SkeletonLine(width = 96.dp, height = 16.dp, modifier = Modifier.padding(start = 12.dp))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f - MAP_WEIGHT)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(3) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonStatTile(Modifier.weight(1f))
                        SkeletonStatTile(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Share of the height the map takes while the stats grid is visible (docs/04_ux_design.md §2.4). */
private const val MAP_WEIGHT = 0.45f
private const val STATS_TOGGLE_MS = 250
