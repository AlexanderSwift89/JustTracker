package com.justtracker.app.ui.detail

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.movableContentOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justtracker.app.R
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.Track
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
import com.justtracker.app.ui.common.appViewModelWithState
import com.justtracker.app.ui.common.FittedText
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
 * Scrubber under (portrait) or beside (landscape) the detail map (docs/04_ux_design.md §2.9): slider
 * over the track distance with "elapsed · %" caption, arrows to step one point, and the values at the cursor.
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
        // Four equal cells whose text scales down when it does not fit: at a large font in the narrow landscape
        // pane the values ran into each other ("20:36:36150") and the labels broke mid-word.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            val cell = Modifier.weight(1f)
            CursorValue(stringResource(R.string.detail_cursor_speed), formatter.speed(cursor.speedMps.toDouble()), cell)
            CursorValue(stringResource(R.string.detail_distance), formatter.distance(cursor.distanceFromStartM), cell)
            CursorValue(stringResource(R.string.detail_cursor_time), TimeFormat.timeOfDay(cursor.timestamp), cell)
            CursorValue(stringResource(R.string.detail_cursor_altitude), cursor.altitudeM?.let { formatter.elevation(it) } ?: "—", cell)
        }
    }
}

@Composable
private fun CursorValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FittedText(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        FittedText(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Map with the whole track, the speed legend and the offline badge. */
@Composable
private fun DetailMap(
    state: TrackDetailUiState,
    formatter: UnitFormatter,
    offline: Boolean,
    onTrackTap: (segment: Int, index: Int) -> Unit,
    onPoiClick: (com.justtracker.app.domain.poi.Poi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.track ?: return
    Box(modifier) {
        TrackMap(
            segments = state.segments,
            modifier = Modifier.fillMaxSize(),
            lineColor = ActivityColors.of(track.activityType),
            maxSpeedMps = track.maxSpeedMps,
            highlight = state.cursor?.point,
            fitToTrack = true,
            showStartFinish = true,
            onTrackTap = onTrackTap,
            pois = state.pois,
            onPoiClick = onPoiClick,
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SpeedLegend(maxSpeedMps = track.maxSpeedMps, formatter = formatter)
            if (offline) MapModeBadge()
        }
    }
}

/** Activity type (tap → type sheet) and the button that folds the stats grid away. */
@Composable
private fun ActivityRow(type: ActivityType, statsVisible: Boolean, onTypeClick: () -> Unit, onToggleStats: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActivityBadge(type, size = 32)
        Text(
            stringResource(type.labelRes()),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f)
                .clickable(onClick = onTypeClick),
        )
        IconButton(onClick = onToggleStats) {
            Icon(
                if (statsVisible) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(if (statsVisible) R.string.detail_hide_stats else R.string.detail_show_stats),
            )
        }
    }
}

/** One tile of the stats grid; a [wide] one takes a whole row. */
private data class StatItem(val label: String, val value: String, val wide: Boolean = false)

/** Tiles of the stats grid; recording time (Start → Stop) is the primary time, moving time secondary (US-08). */
@Composable
private fun statTiles(track: Track, formatter: UnitFormatter): List<StatItem> {
    val pace = if (track.activityType == ActivityType.WALK || track.activityType == ActivityType.RUN) {
        formatter.pace(if (track.distanceM > 0) (track.movingTimeMs / 1000.0) / track.distanceM else null)
    } else {
        null
    }
    return buildList {
        add(StatItem(stringResource(R.string.detail_distance), formatter.distance(track.distanceM)))
        add(StatItem(stringResource(R.string.detail_recording_time), TimeFormat.duration(track.recordingTimeMs())))
        add(StatItem(stringResource(R.string.detail_moving_time), TimeFormat.duration(track.movingTimeMs)))
        add(StatItem(stringResource(R.string.detail_avg_speed), formatter.speed(track.avgSpeedMps)))
        add(StatItem(stringResource(R.string.detail_max_speed), formatter.speed(track.maxSpeedMps)))
        add(StatItem(stringResource(R.string.detail_elevation_gain), formatter.elevation(track.elevationGainM, signed = true)))
        add(StatItem(stringResource(R.string.detail_elevation_loss), formatter.elevation(-track.elevationLossM, signed = true)))
        if (pace != null) add(StatItem(stringResource(R.string.detail_pace), pace))
        add(StatItem(stringResource(R.string.detail_points), track.pointCount.toString()))
        if (track.pausedTimeMs > 0) add(StatItem(stringResource(R.string.detail_paused_time), TimeFormat.duration(track.pausedTimeMs)))
        // "23 сент. 2026 г., 20:36" does not fit half a phone's width: a half-width tile showed "23 сент." only.
        add(StatItem(stringResource(R.string.detail_started), TimeFormat.dateTime(track.startedAt), wide = true))
    }
}

/**
 * Rows of the two-column stats grid: items pair up in order, a [wide] one takes a row of its own (the item
 * before it then stays alone) — the same placement as `GridItemSpan(maxLineSpan)` in the portrait grid.
 */
internal fun <T> gridRows(items: List<T>, wide: (T) -> Boolean): List<List<T>> = buildList {
    var pending = emptyList<T>()
    items.forEach { item ->
        if (wide(item)) {
            if (pending.isNotEmpty()) add(pending)
            pending = emptyList()
            add(listOf(item))
        } else {
            pending = pending + item
            if (pending.size == 2) {
                add(pending)
                pending = emptyList()
            }
        }
    }
    if (pending.isNotEmpty()) add(pending)
}

/** Two tiles per row (a wide tile alone) without a lazy container, for the scrollable side pane of the landscape layout. */
@Composable
private fun StatTileRows(tiles: List<StatItem>, modifier: Modifier = Modifier) {
    val rows = gridRows(tiles) { it.wide }
    Column(
        modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item -> StatTile(label = item.label, value = item.value, modifier = Modifier.weight(1f)) }
                if (row.size == 1 && !row[0].wide) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(
    trackId: Long,
    onBack: () -> Unit,
    viewModel: TrackDetailViewModel = appViewModelWithState(key = "detail-$trackId") { c, saved -> TrackDetailViewModel(c, trackId, saved) },
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
    // Dialogs and the sheet survive an activity recreation (theme / language change) too.
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showTypeSheet by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    // Stats grid is shown by default; the user can fold it away to give the map the whole screen while the
    // scrubber and its values stay put (docs/04_ux_design.md §2.4). Survives rotation, not navigation.
    var statsVisible by rememberSaveable { mutableStateOf(true) }
    val mapWeight by animateFloatAsState(if (statsVisible) MAP_WEIGHT else 1f, tween(STATS_TOGGLE_MS), label = "mapWeight")
    val gridState = rememberLazyGridState()
    val paneScroll = rememberScrollState()
    val exportFailed = stringResource(R.string.detail_export_failed)
    val chooserTitle = stringResource(R.string.detail_export_chooser)
    // The map moves between the portrait column and the landscape row without being recreated: its
    // MapView, loaded tiles and camera survive the rotation (docs/04_ux_design.md §2.4).
    val mapArea = remember {
        movableContentOf { modifier: Modifier, s: TrackDetailUiState, f: UnitFormatter, offline: Boolean ->
            DetailMap(
                state = s,
                formatter = f,
                offline = offline,
                onTrackTap = viewModel::onTrackTap,
                onPoiClick = { poi -> poiCardViewModel.open(poi, distanceM = null) },
                modifier = modifier,
            )
        }
    }

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
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // A wide, short window (a phone in landscape) puts the map beside the panel: stacked, 45 % of the
            // height left the map about 50 dp tall.
            val twoPane = maxWidth > maxHeight && maxWidth >= TWO_PANE_MIN_WIDTH
            val paneWidth = (maxWidth * PANE_WIDTH_SHARE).coerceIn(PANE_MIN_WIDTH, PANE_MAX_WIDTH)
            val skeleton = rememberSkeletonVisible(!state.loaded)
            if (track == null) {
                when {
                    state.loaded -> Text(stringResource(R.string.detail_not_found), modifier = Modifier.padding(24.dp))
                    skeleton -> TrackDetailSkeleton(twoPane, paneWidth)
                }
                return@BoxWithConstraints
            }
            val offline = appSettings.mapMode == MapMode.OFFLINE
            val tiles = statTiles(track, formatter)
            val cursorPanel = @Composable {
                state.cursor?.let { cursor ->
                    TrackCursorPanel(cursor = cursor, formatter = formatter, onFraction = viewModel::scrubToFraction, onStep = viewModel::stepCursor)
                }
            }
            val activityRow = @Composable {
                ActivityRow(
                    type = track.activityType,
                    statsVisible = statsVisible,
                    onTypeClick = { showTypeSheet = true },
                    onToggleStats = { statsVisible = !statsVisible },
                )
            }
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    mapArea(Modifier.weight(1f).fillMaxHeight(), state, formatter, offline)
                    // The map already has the whole height here, so the pane scrolls as one piece.
                    Column(
                        Modifier
                            .width(paneWidth)
                            .fillMaxHeight()
                            .verticalScroll(paneScroll),
                    ) {
                        cursorPanel()
                        activityRow()
                        if (statsVisible) StatTileRows(tiles, Modifier.padding(bottom = 8.dp))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    mapArea(Modifier.fillMaxWidth().weight(mapWeight), state, formatter, offline)
                    cursorPanel()
                    activityRow()
                    // The grid takes what the map gives up; it leaves the composition once the map owns the whole height.
                    if (mapWeight < 1f) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            state = gridState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f - mapWeight),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(tiles, span = { item -> GridItemSpan(if (item.wide) maxLineSpan else 1) }) { item ->
                                StatTile(label = item.label, value = item.value)
                            }
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
            // Six rows do not fit a landscape phone: the sheet content scrolls.
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
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
 * (docs/04_ux_design.md §7): map area, scrubber row, activity row and the first tiles of the grid —
 * stacked in portrait, map beside a pane in landscape.
 */
@Composable
private fun TrackDetailSkeleton(twoPane: Boolean, paneWidth: Dp) {
    SkeletonGroup(Modifier.fillMaxSize()) {
        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                SkeletonBox(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    shape = MaterialTheme.shapes.extraSmall,
                )
                Column(Modifier.width(paneWidth)) {
                    SkeletonCursorRows()
                    SkeletonActivityRow()
                    SkeletonTileRows(rows = 2)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                SkeletonBox(
                    Modifier
                        .fillMaxWidth()
                        .weight(MAP_WEIGHT),
                    shape = MaterialTheme.shapes.extraSmall,
                )
                SkeletonCursorRows()
                SkeletonActivityRow()
                SkeletonTileRows(rows = 3, modifier = Modifier.weight(1f - MAP_WEIGHT))
            }
        }
    }
}

@Composable
private fun SkeletonCursorRows() {
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
}

@Composable
private fun SkeletonActivityRow() {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBox(Modifier.size(32.dp), shape = androidx.compose.foundation.shape.CircleShape)
        SkeletonLine(width = 96.dp, height = 16.dp, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun SkeletonTileRows(rows: Int, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonStatTile(Modifier.weight(1f))
                SkeletonStatTile(Modifier.weight(1f))
            }
        }
    }
}

/** Share of the height the map takes while the stats grid is visible (docs/04_ux_design.md §2.4). */
private const val MAP_WEIGHT = 0.45f
private const val STATS_TOGGLE_MS = 250

/** Landscape layout (map beside the pane) from this width on, when the window is wider than tall. */
private val TWO_PANE_MIN_WIDTH = 560.dp
private const val PANE_WIDTH_SHARE = 0.42f
private val PANE_MIN_WIDTH = 300.dp
private val PANE_MAX_WIDTH = 420.dp
