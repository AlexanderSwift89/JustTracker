package com.justtracker.app.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.justtracker.app.BuildConfig
import com.justtracker.app.R
import com.justtracker.app.di.AppContainer
import com.justtracker.app.domain.model.AppLanguage
import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.domain.maps.RegionStatus
import com.justtracker.app.ui.maps.MapModeDialog
import com.justtracker.app.ui.maps.labelRes
import com.justtracker.app.domain.model.AppSettings
import com.justtracker.app.domain.model.ThemeMode
import com.justtracker.app.domain.model.UnitSystem
import com.justtracker.app.ui.common.appViewModel
import com.justtracker.app.util.AppLocale
import com.justtracker.app.util.labelRes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(container: AppContainer) : ViewModel() {
    private val repo = container.settingsRepository
    private val mapModeController = container.mapModeController
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val readyRegions = container.offlineRegionStore.regions
        .map { list -> list.filter { it.status == RegionStatus.READY } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Persist, then let AppCompat apply the locale (recreates the activity when it changes). */
    fun setLanguage(language: AppLanguage) = viewModelScope.launch {
        repo.setLanguage(language)
        AppLocale.apply(language)
    }
    fun setUnits(units: UnitSystem) = viewModelScope.launch { repo.setUnits(units) }
    fun setMapMode(mode: MapMode) = viewModelScope.launch { mapModeController.setMode(mode) }
    fun setTheme(theme: ThemeMode) = viewModelScope.launch { repo.setTheme(theme) }
    fun setMaxAccuracy(m: Int) = viewModelScope.launch { repo.setMaxAccuracy(m) }
    fun setKeepScreenOn(on: Boolean) = viewModelScope.launch { repo.setKeepScreenOn(on) }
    fun setPoiEnabled(on: Boolean) = viewModelScope.launch { repo.setPoiEnabled(on) }
    fun setPoiAutoSpeak(on: Boolean) = viewModelScope.launch { repo.setPoiAutoSpeak(on) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenOfflineMaps: () -> Unit = {},
    viewModel: SettingsViewModel = appViewModel { SettingsViewModel(it) },
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val readyRegions by viewModel.readyRegions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var languageDialog by remember { mutableStateOf(false) }
    var mapModeDialog by remember { mutableStateOf(false) }
    var unitsDialog by remember { mutableStateOf(false) }
    var themeDialog by remember { mutableStateOf(false) }
    var accuracy by remember(settings.maxAccuracyM) { mutableFloatStateOf(settings.maxAccuracyM.toFloat()) }
    val privacyUrl = stringResource(R.string.settings_privacy_url)
    val licensesUrl = stringResource(R.string.settings_licenses_url)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_language)) },
                supportingContent = { Text(stringResource((settings.language ?: AppLanguage.forDevice()).labelRes())) },
                modifier = Modifier.clickable { languageDialog = true },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_units)) },
                supportingContent = { Text(stringResource(settings.units.labelRes())) },
                modifier = Modifier.clickable { unitsDialog = true },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_theme)) },
                supportingContent = { Text(stringResource(settings.theme.labelRes())) },
                modifier = Modifier.clickable { themeDialog = true },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_accuracy)) },
                supportingContent = {
                    Column {
                        Text(stringResource(R.string.settings_accuracy_desc, accuracy.toInt()))
                        Slider(
                            value = accuracy,
                            onValueChange = { accuracy = it },
                            onValueChangeFinished = { viewModel.setMaxAccuracy(accuracy.toInt()) },
                            valueRange = 10f..100f,
                            steps = 8,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_keep_screen_on)) },
                trailingContent = { Switch(checked = settings.keepScreenOn, onCheckedChange = null) },
                modifier = Modifier.toggleable(value = settings.keepScreenOn, role = Role.Switch, onValueChange = viewModel::setKeepScreenOn),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                stringResource(R.string.settings_poi_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_poi_enabled)) },
                supportingContent = { Text(stringResource(R.string.settings_poi_enabled_desc)) },
                trailingContent = { Switch(checked = settings.poiEnabled, onCheckedChange = null) },
                modifier = Modifier.toggleable(value = settings.poiEnabled, role = Role.Switch, onValueChange = viewModel::setPoiEnabled),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_poi_auto_speak)) },
                supportingContent = { Text(stringResource(R.string.settings_poi_auto_speak_desc)) },
                trailingContent = {
                    Switch(
                        checked = settings.poiEnabled && settings.poiAutoSpeak,
                        enabled = settings.poiEnabled,
                        onCheckedChange = null,
                    )
                },
                modifier = Modifier.toggleable(
                    value = settings.poiEnabled && settings.poiAutoSpeak,
                    enabled = settings.poiEnabled,
                    role = Role.Switch,
                    onValueChange = viewModel::setPoiAutoSpeak,
                ),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                stringResource(R.string.settings_maps_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_map_mode)) },
                supportingContent = {
                    Text(
                        stringResource(settings.mapMode.labelRes()) +
                            if (settings.mapMode == MapMode.OFFLINE && readyRegions.isEmpty()) " · " + stringResource(R.string.settings_map_mode_no_regions) else "",
                    )
                },
                modifier = Modifier.clickable { mapModeDialog = true },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_maps_manage)) },
                supportingContent = {
                    Text(
                        if (readyRegions.isEmpty()) {
                            stringResource(R.string.settings_maps_summary_none)
                        } else {
                            pluralStringResource(
                                R.plurals.settings_maps_summary,
                                readyRegions.size,
                                readyRegions.size,
                                android.text.format.Formatter.formatShortFileSize(context, readyRegions.sumOf { it.sizeBytes }),
                            )
                        },
                    )
                },
                modifier = Modifier.clickable(onClick = onOpenOfflineMaps),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_privacy)) },
                supportingContent = { Text(privacyUrl) },
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, privacyUrl.toUri())) }
                },
            )
            // Third-party notices (Mapsforge is LGPL-3.0 — the user must be told; docs/07_security.md §7).
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_licenses)) },
                supportingContent = { Text(licensesUrl) },
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, licensesUrl.toUri())) }
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_version)) },
                supportingContent = { Text(BuildConfig.VERSION_NAME) },
            )
            Text(
                stringResource(R.string.settings_poi_attribution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Text(
                stringResource(R.string.settings_map_attribution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (mapModeDialog) {
        MapModeDialog(
            current = settings.mapMode,
            readyRegions = readyRegions.size,
            onConfirm = {
                mapModeDialog = false
                if (it != settings.mapMode) viewModel.setMapMode(it)
            },
            onDismiss = { mapModeDialog = false },
        )
    }
    if (languageDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_language),
            options = AppLanguage.entries.map { it to stringResource(it.labelRes()) },
            selected = settings.language ?: AppLanguage.forDevice(),
            onSelect = {
                languageDialog = false
                if (it != settings.language) viewModel.setLanguage(it)
            },
            onDismiss = { languageDialog = false },
        )
    }
    if (unitsDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_units),
            options = UnitSystem.entries.map { it to stringResource(it.labelRes()) },
            selected = settings.units,
            onSelect = {
                viewModel.setUnits(it)
                unitsDialog = false
            },
            onDismiss = { unitsDialog = false },
        )
    }
    if (themeDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries.map { it to stringResource(it.labelRes()) },
            selected = settings.theme,
            onSelect = {
                viewModel.setTheme(it)
                themeDialog = false
            },
            onDismiss = { themeDialog = false },
        )
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    androidx.compose.foundation.layout.Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = value == selected, onClick = { onSelect(value) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun UnitSystem.labelRes() = when (this) {
    UnitSystem.METRIC -> R.string.settings_units_metric
    UnitSystem.IMPERIAL -> R.string.settings_units_imperial
}

private fun ThemeMode.labelRes() = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}
