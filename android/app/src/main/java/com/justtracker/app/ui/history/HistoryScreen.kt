package com.justtracker.app.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.justtracker.app.R
import com.justtracker.app.data.repo.SettingsRepository
import com.justtracker.app.data.repo.TrackRepository
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.UnitSystem
import com.justtracker.app.ui.common.ActivityBadge
import com.justtracker.app.ui.common.EmptyState
import com.justtracker.app.ui.common.SkeletonGroup
import com.justtracker.app.ui.common.SkeletonTrackCard
import com.justtracker.app.ui.common.DeleteDialog
import com.justtracker.app.ui.common.RenameDialog
import com.justtracker.app.ui.common.appViewModel
import com.justtracker.app.ui.common.rememberSkeletonVisible
import com.justtracker.app.util.TimeFormat
import com.justtracker.app.util.UnitFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(val tracks: List<Track> = emptyList(), val units: UnitSystem = UnitSystem.METRIC, val loaded: Boolean = false)

class HistoryViewModel(private val repo: TrackRepository, settings: SettingsRepository) : ViewModel() {
    val state = combine(repo.observeFinishedTracks(), settings.settings) { tracks, settings ->
        HistoryUiState(tracks, settings.units, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun rename(id: Long, name: String) = viewModelScope.launch { repo.rename(id, name) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onOpenTrack: (Long) -> Unit,
    viewModel: HistoryViewModel = appViewModel { c -> HistoryViewModel(c.trackRepository, c.settingsRepository) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val formatter = remember(state.units) { UnitFormatter(context, state.units) }
    // Kept by id so an open dialog survives an activity recreation (theme / language change).
    var renameTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    val renameTarget = state.tracks.firstOrNull { it.id == renameTargetId }
    val deleteTarget = state.tracks.firstOrNull { it.id == deleteTargetId }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.history_title)) }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        val skeleton = rememberSkeletonVisible(!state.loaded)
        if (skeleton) {
            // Placeholder cards with the footprint of the coming list (docs/04_ux_design.md §7).
            SkeletonGroup(Modifier.padding(padding)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(SKELETON_CARDS) { SkeletonTrackCard() }
                }
            }
        } else if (state.loaded && state.tracks.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Route,
                title = stringResource(R.string.history_empty_title),
                body = stringResource(R.string.history_empty_body),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.tracks, key = { it.id }) { track ->
                    TrackCard(
                        track = track,
                        formatter = formatter,
                        onClick = { onOpenTrack(track.id) },
                        onRename = { renameTargetId = track.id },
                        onDelete = { deleteTargetId = track.id },
                    )
                }
            }
        }
    }

    renameTarget?.let { track ->
        RenameDialog(
            initial = track.name,
            onDismiss = { renameTargetId = null },
            onSave = { name ->
                viewModel.rename(track.id, name)
                renameTargetId = null
            },
        )
    }
    deleteTarget?.let { track ->
        DeleteDialog(
            onDismiss = { deleteTargetId = null },
            onConfirm = {
                viewModel.delete(track.id)
                deleteTargetId = null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackCard(track: Track, formatter: UnitFormatter, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActivityBadge(track.activityType)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(track.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        TimeFormat.dateTime(track.startedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Primary row: distance · recording time · average; secondary: moving time and max speed.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatter.distance(track.distanceM), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(TimeFormat.duration(track.recordingTimeMs()), style = MaterialTheme.typography.bodyLarge)
                Text(formatter.speed(track.avgSpeedMps), style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                stringResource(
                    R.string.history_secondary_format,
                    TimeFormat.duration(track.movingTimeMs),
                    formatter.speed(track.maxSpeedMps),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = {
                menuOpen = false
                onRename()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = {
                menuOpen = false
                onDelete()
            })
        }
    }
}

private const val SKELETON_CARDS = 4
