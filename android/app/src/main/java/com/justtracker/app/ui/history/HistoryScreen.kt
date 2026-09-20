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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.justtracker.app.R
import com.justtracker.app.data.repo.TrackRepository
import com.justtracker.app.di.AppContainer
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.UnitSystem
import com.justtracker.app.ui.common.ActivityBadge
import com.justtracker.app.ui.common.EmptyState
import com.justtracker.app.ui.common.appViewModel
import com.justtracker.app.util.TimeFormat
import com.justtracker.app.util.UnitFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(val tracks: List<Track> = emptyList(), val units: UnitSystem = UnitSystem.METRIC, val loaded: Boolean = false)

class HistoryViewModel(container: AppContainer) : ViewModel() {
    private val repo: TrackRepository = container.trackRepository
    val state = combine(repo.observeFinishedTracks(), container.settingsRepository.settings) { tracks, settings ->
        HistoryUiState(tracks, settings.units, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun rename(id: Long, name: String) = viewModelScope.launch { repo.rename(id, name) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onOpenTrack: (Long) -> Unit,
    viewModel: HistoryViewModel = appViewModel { HistoryViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val formatter = remember(state.units) { UnitFormatter(context, state.units) }
    var renameTarget by remember { mutableStateOf<Track?>(null) }
    var deleteTarget by remember { mutableStateOf<Track?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.history_title)) }) }) { padding ->
        if (state.loaded && state.tracks.isEmpty()) {
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
                        onRename = { renameTarget = track },
                        onDelete = { deleteTarget = track },
                    )
                }
            }
        }
    }

    renameTarget?.let { track ->
        RenameDialog(
            initial = track.name,
            onDismiss = { renameTarget = null },
            onSave = { name ->
                viewModel.rename(track.id, name)
                renameTarget = null
            },
        )
    }
    deleteTarget?.let { track ->
        DeleteDialog(
            onDismiss = { deleteTarget = null },
            onConfirm = {
                viewModel.delete(track.id)
                deleteTarget = null
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

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= TrackRepository.MAX_NAME_LENGTH) name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.detail_rename_hint)) },
                modifier = Modifier.width(320.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun DeleteDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_delete_dialog_title)) },
        text = { Text(stringResource(R.string.history_delete_dialog_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
