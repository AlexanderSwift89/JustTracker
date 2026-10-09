package com.justtracker.app.ui.detail

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justtracker.app.R
import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.track.AccelerationEpisode
import com.justtracker.app.ui.common.ActivityBadge
import com.justtracker.app.ui.common.StatTile
import com.justtracker.app.ui.common.appViewModelWithState
import com.justtracker.app.ui.common.rememberSkeletonVisible
import com.justtracker.app.ui.common.DeleteDialog
import com.justtracker.app.ui.common.RenameDialog
import com.justtracker.app.util.UnitFormatter
import com.justtracker.app.util.labelRes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(
    trackId: Long,
    onBack: () -> Unit,
    viewModel: TrackDetailViewModel = appViewModelWithState(key = "detail-$trackId") { c, saved ->
        TrackDetailViewModel(c.trackRepository, c.settingsRepository, c.gpxExporter, trackId, saved, c.dispatchers)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Read only where it is shown (the cursor panel and the map's ring): the slider does not recompose the screen.
    val cursorState = viewModel.cursor.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val formatter = remember(state.units) { UnitFormatter(context, state.units) }
    var menuOpen by remember { mutableStateOf(false) }
    // Dialogs and the sheet survive an activity recreation (theme / language change) too.
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showTypeSheet by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showEpisodes by rememberSaveable { mutableStateOf(false) }
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
                onMetricChange = viewModel::setLineMetric,
                highlight = { cursorState.value?.point },
                modifier = modifier,
            )
        }
    }

    val track = state.track
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // An episode moves the scrubber to its start; in landscape the pane scrolls back up to the scrubber's values.
    val onEpisode = { episode: AccelerationEpisode ->
        viewModel.onEpisodeTap(episode)
        scope.launch { paneScroll.animateScrollTo(0) }
        Unit
    }
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
                                val intent = viewModel.shareIntent()
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
            val offline = state.mapMode == MapMode.OFFLINE
            val tiles = statTiles(track, state.acceleration, formatter)
            val hasEpisodes = state.acceleration.hasEpisodes
            val cursorPanel = @Composable {
                cursorState.value?.let { cursor ->
                    TrackCursorPanel(
                        cursor = cursor,
                        metric = state.lineMetric,
                        formatter = formatter,
                        onFraction = viewModel::scrubToFraction,
                        onStep = viewModel::stepCursor,
                    )
                }
            }
            val episodes = @Composable { modifier: Modifier ->
                EpisodesSection(
                    state.acceleration,
                    formatter,
                    onEpisode = onEpisode,
                    onShowAll = { showEpisodes = true },
                    modifier = modifier,
                )
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
                        if (statsVisible) {
                            StatTileRows(tiles, Modifier.padding(bottom = 8.dp))
                            if (hasEpisodes) episodes(Modifier.padding(bottom = 8.dp))
                        }
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
                                StatTile(label = item.label, value = item.value, contentDescription = item.spoken)
                            }
                            if (hasEpisodes) {
                                item(span = { GridItemSpan(maxLineSpan) }) { episodes(Modifier) }
                            }
                        }
                    }
                }
            }
        }
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
    if (showEpisodes && state.acceleration.hasEpisodes) {
        EpisodesSheet(state.acceleration, formatter, onEpisode = onEpisode, onDismiss = { showEpisodes = false })
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

/** Share of the height the map takes while the stats grid is visible (docs/04_ux_design.md §2.4). */
internal const val MAP_WEIGHT = 0.45f
internal const val STATS_TOGGLE_MS = 250

/** Landscape layout (map beside the pane) from this width on, when the window is wider than tall. */
private val TWO_PANE_MIN_WIDTH = 560.dp
private const val PANE_WIDTH_SHARE = 0.42f
private val PANE_MIN_WIDTH = 300.dp
private val PANE_MAX_WIDTH = 420.dp
