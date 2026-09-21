package com.justtracker.app.ui.maps

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.justtracker.app.R
import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.domain.maps.MapModeReason
import com.justtracker.app.domain.maps.MapModeSuggestion

@StringRes
fun MapMode.labelRes(): Int = when (this) {
    MapMode.ONLINE -> R.string.map_mode_online
    MapMode.OFFLINE -> R.string.map_mode_offline
}

@StringRes
private fun MapMode.descriptionRes(): Int = when (this) {
    MapMode.ONLINE -> R.string.map_mode_online_desc
    MapMode.OFFLINE -> R.string.map_mode_offline_desc
}

/**
 * Settings → Map mode: the explicit choice (US-22). Nothing changes until "Apply" — the dialog
 * explains what each mode does and warns when no region is downloaded.
 */
@Composable
fun MapModeDialog(current: MapMode, readyRegions: Int, onConfirm: (MapMode) -> Unit, onDismiss: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_map_mode)) },
        text = {
            Column(Modifier.selectableGroup()) {
                MapMode.entries.forEach { mode ->
                    androidx.compose.foundation.layout.Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = mode == selected, role = Role.RadioButton, onClick = { selected = mode })
                            .padding(vertical = 8.dp),
                    ) {
                        RadioButton(selected = mode == selected, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(stringResource(mode.labelRes()), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(mode.descriptionRes()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    if (readyRegions == 0) {
                        stringResource(R.string.map_mode_regions_none)
                    } else {
                        pluralStringResource(R.plurals.map_mode_regions_ready, readyRegions, readyRegions)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (readyRegions == 0 && selected == MapMode.OFFLINE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Connectivity changed: ask before switching (never automatic). "Keep" silences the prompt until connectivity changes again. */
@Composable
fun MapModePromptDialog(suggestion: MapModeSuggestion, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val (title, body) = when (suggestion.reason) {
        MapModeReason.INTERNET_LOST -> R.string.map_mode_prompt_lost_title to R.string.map_mode_prompt_lost_body
        MapModeReason.INTERNET_RESTORED -> R.string.map_mode_prompt_restored_title to R.string.map_mode_prompt_restored_body
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.map_mode_prompt_switch, stringResource(suggestion.target.labelRes()))) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.map_mode_prompt_keep)) } },
    )
}
