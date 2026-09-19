package io.treklog.app.ui.detail

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.treklog.app.R
import io.treklog.app.domain.model.ActivityType
import io.treklog.app.ui.common.ActivityBadge
import io.treklog.app.ui.common.StatTile
import io.treklog.app.ui.common.TrackMap
import io.treklog.app.ui.common.appViewModel
import io.treklog.app.ui.history.DeleteDialog
import io.treklog.app.ui.history.RenameDialog
import io.treklog.app.ui.theme.ActivityColors
import io.treklog.app.util.TimeFormat
import io.treklog.app.util.UnitFormatter
import io.treklog.app.util.labelRes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(
    trackId: Long,
    onBack: () -> Unit,
    viewModel: TrackDetailViewModel = appViewModel(key = "detail-$trackId") { TrackDetailViewModel(it, trackId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
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
            TrackMap(
                segments = state.segments,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.45f),
                lineColor = ActivityColors.of(track.activityType),
                fitToTrack = true,
                showStartFinish = true,
            )
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
            val tiles = buildList {
                add(stringResource(R.string.detail_distance) to formatter.distance(track.distanceM))
                add(stringResource(R.string.detail_moving_time) to TimeFormat.duration(track.movingTimeMs))
                add(stringResource(R.string.detail_avg_speed) to formatter.speed(track.avgSpeedMps))
                add(stringResource(R.string.detail_max_speed) to formatter.speed(track.maxSpeedMps))
                add(stringResource(R.string.detail_elevation_gain) to formatter.elevation(track.elevationGainM, signed = true))
                add(stringResource(R.string.detail_elevation_loss) to formatter.elevation(-track.elevationLossM, signed = true))
                if (pace != null) add(stringResource(R.string.detail_pace) to pace)
                add(stringResource(R.string.detail_points) to track.pointCount.toString())
                add(stringResource(R.string.detail_total_time) to TimeFormat.duration(track.totalTimeMs))
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
