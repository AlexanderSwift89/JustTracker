package com.justtracker.app.ui.maps

import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justtracker.app.R
import com.justtracker.app.domain.maps.RegionError
import com.justtracker.app.domain.maps.RegionStatus
import com.justtracker.app.ui.common.SkeletonGroup
import com.justtracker.app.ui.common.SkeletonListItem
import com.justtracker.app.ui.common.appViewModel
import com.justtracker.app.ui.common.rememberSkeletonVisible

/** Settings → Offline maps: catalogue downloads, imported files, Wi-Fi-only switch (US-19, US-20). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(
    onBack: () -> Unit,
    viewModel: OfflineMapsViewModel = appViewModel { OfflineMapsViewModel(it) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    // Kept by region id so an open confirmation survives an activity recreation.
    var confirmDownloadId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val confirmDownload = state.catalog.firstOrNull { it.id == confirmDownloadId }
    val confirmDelete = (state.catalog + state.imported).firstOrNull { it.id == confirmDeleteId }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.import(uri, displayName(context, uri))
    }

    val errorText = state.error?.let { stringResource(it.messageRes()) }
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbar.showSnackbar(errorText)
            viewModel.consumeError()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text(stringResource(R.string.maps_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, enabled = !state.importing) {
                        Icon(Icons.Filled.FileOpen, contentDescription = stringResource(R.string.maps_import))
                    }
                },
            )
        },
    ) { padding ->
        val skeleton = rememberSkeletonVisible(!state.loaded)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.maps_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.maps_wifi_only)) },
                    supportingContent = { Text(stringResource(R.string.maps_wifi_only_desc)) },
                    trailingContent = { Switch(checked = state.wifiOnly, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = state.wifiOnly, role = Role.Switch, onValueChange = viewModel::setWifiOnly),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.maps_storage)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.maps_storage_desc,
                                Formatter.formatShortFileSize(context, state.usedBytes),
                                Formatter.formatShortFileSize(context, state.freeBytes),
                            ),
                        )
                    },
                )
                if (state.importing) LinearProgressIndicator(Modifier.padding(horizontal = 16.dp))
                if (!state.canDownload) {
                    Text(
                        stringResource(R.string.maps_no_external_storage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionHeader(stringResource(R.string.maps_catalog_header))
            }
            if (skeleton) {
                // Catalogue rows are still being read (catalogue asset + Room): same footprint as the list.
                item { SkeletonGroup { Column { repeat(SKELETON_ROWS) { SkeletonListItem() } } } }
            }
            items(state.catalog, key = { "c-" + it.id }) { row ->
                RegionListItem(
                    row = row,
                    state = state,
                    onDownload = { confirmDownloadId = row.id },
                    onCancel = { viewModel.cancel(row.id) },
                    onDelete = { confirmDeleteId = row.id },
                    onRetry = { viewModel.download(row.id) },
                )
            }
            if (state.imported.isNotEmpty()) {
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SectionHeader(stringResource(R.string.maps_imported_header))
                }
                items(state.imported, key = { "i-" + it.id }) { row ->
                    RegionListItem(
                        row = row,
                        state = state,
                        onDownload = {},
                        onCancel = {},
                        onDelete = { confirmDeleteId = row.id },
                        onRetry = {},
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.maps_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    confirmDownload?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDownloadId = null },
            title = { Text(stringResource(R.string.maps_download_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.maps_download_confirm_body,
                        row.name(state.language),
                        Formatter.formatShortFileSize(context, row.sizeBytes),
                        stringResource(if (state.wifiOnly) R.string.maps_download_via_wifi else R.string.maps_download_via_any),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDownloadId = null
                    viewModel.download(row.id)
                }) { Text(stringResource(R.string.maps_download)) }
            },
            dismissButton = { TextButton(onClick = { confirmDownloadId = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    confirmDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text(stringResource(R.string.maps_delete_confirm_title)) },
            text = { Text(stringResource(R.string.maps_delete_confirm_body, row.name(state.language))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteId = null
                    viewModel.delete(row.id)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteId = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private const val SKELETON_ROWS = 6

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun RegionListItem(
    row: RegionRow,
    state: OfflineMapsUiState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val region = row.region
    val size = Formatter.formatShortFileSize(context, row.sizeBytes)
    val status = when (region?.status) {
        null -> size
        RegionStatus.QUEUED -> stringResource(if (region.waitingForNetwork) waitingRes(state.wifiOnly) else R.string.maps_status_queued)
        RegionStatus.DOWNLOADING -> when {
            region.waitingForNetwork -> stringResource(waitingRes(state.wifiOnly))
            region.progress != null -> stringResource(R.string.maps_status_downloading, (region.progress * 100).toInt())
            else -> stringResource(R.string.maps_status_queued)
        }
        RegionStatus.VERIFYING -> stringResource(R.string.maps_status_verifying)
        RegionStatus.READY -> stringResource(R.string.maps_status_ready, size)
        RegionStatus.ERROR -> stringResource((region.error ?: RegionError.UNKNOWN).messageRes())
    }
    ListItem(
        headlineContent = { Text(row.name(state.language)) },
        supportingContent = {
            Column {
                Text(status)
                if (region?.status == RegionStatus.DOWNLOADING && region.progress != null) {
                    LinearProgressIndicator(progress = { region.progress }, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        trailingContent = {
            when (region?.status) {
                null -> IconButton(onClick = onDownload, enabled = state.canDownload) {
                    Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.maps_download))
                }
                RegionStatus.QUEUED, RegionStatus.DOWNLOADING, RegionStatus.VERIFYING -> IconButton(onClick = onCancel) {
                    if (region.status == RegionStatus.VERIFYING) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.maps_cancel_download))
                    }
                }
                RegionStatus.READY -> IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.maps_delete))
                }
                RegionStatus.ERROR -> IconButton(onClick = if (row.catalog != null) onRetry else onDelete) {
                    Icon(
                        if (row.catalog != null) Icons.Filled.Refresh else Icons.Filled.Delete,
                        contentDescription = stringResource(if (row.catalog != null) R.string.maps_retry else R.string.maps_delete),
                    )
                }
            }
        },
        leadingContent = {
            when (region?.status) {
                RegionStatus.READY -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                RegionStatus.ERROR -> Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                else -> Icon(Icons.Filled.Map, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

private fun waitingRes(wifiOnly: Boolean) = if (wifiOnly) R.string.maps_status_waiting_wifi else R.string.maps_status_waiting_network

private fun RegionError.messageRes(): Int = when (this) {
    RegionError.NO_SPACE -> R.string.maps_status_error_space
    RegionError.NETWORK -> R.string.maps_status_error_network
    RegionError.CORRUPT -> R.string.maps_status_error_corrupt
    RegionError.LOST, RegionError.UNKNOWN -> R.string.maps_status_error_unknown
}

private fun displayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
