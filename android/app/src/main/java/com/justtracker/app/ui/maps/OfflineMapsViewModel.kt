package com.justtracker.app.ui.maps

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justtracker.app.data.maps.CatalogRegion
import com.justtracker.app.di.AppContainer
import com.justtracker.app.domain.maps.OfflineRegion
import com.justtracker.app.domain.maps.RegionError
import com.justtracker.app.domain.maps.RegionSource
import com.justtracker.app.domain.model.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One list row: a catalogue entry (with its download state, if any) or an imported file. */
data class RegionRow(val catalog: CatalogRegion?, val region: OfflineRegion?) {
    val id: String get() = catalog?.id ?: region!!.id
    val sizeBytes: Long get() = region?.sizeBytes?.takeIf { it > 0 } ?: catalog?.sizeBytes ?: 0L
    fun name(language: AppLanguage): String = region?.name(language) ?: catalog!!.name(language)
}

data class OfflineMapsUiState(
    val loaded: Boolean = false,
    val catalog: List<RegionRow> = emptyList(),
    val imported: List<RegionRow> = emptyList(),
    val wifiOnly: Boolean = true,
    val canDownload: Boolean = true,
    val freeBytes: Long = 0L,
    val usedBytes: Long = 0L,
    val language: AppLanguage = AppLanguage.EN,
    /** One-shot error for a snackbar; cleared by [OfflineMapsViewModel.consumeError]. */
    val error: RegionError? = null,
    val importing: Boolean = false,
)

class OfflineMapsViewModel(private val container: AppContainer) : ViewModel() {
    private val store = container.offlineRegionStore
    private val settings = container.settingsRepository
    private val transient = MutableStateFlow(Transient())

    private data class Transient(val error: RegionError? = null, val importing: Boolean = false, val tick: Int = 0)

    val state: StateFlow<OfflineMapsUiState> = combine(
        store.regions,
        settings.settings,
        transient,
    ) { regions, prefs, t ->
        val catalog = store.catalog()
        val byId = regions.associateBy { it.id }
        val language = prefs.language ?: AppLanguage.forDevice()
        OfflineMapsUiState(
            loaded = true,
            catalog = catalog.map { RegionRow(it, byId[it.id]) },
            imported = regions.filter { it.source == RegionSource.IMPORT }.map { RegionRow(null, it) }
                .sortedBy { it.name(language).lowercase() },
            wifiOnly = prefs.mapsWifiOnly,
            canDownload = store.canDownload,
            freeBytes = store.freeBytes(),
            usedBytes = regions.sumOf { it.sizeBytes },
            language = language,
            error = t.error,
            importing = t.importing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineMapsUiState())

    fun download(id: String) = viewModelScope.launch {
        val error = store.download(id)
        if (error != null) transient.value = transient.value.copy(error = error)
    }

    fun cancel(id: String) = viewModelScope.launch { store.cancel(id) }
    fun delete(id: String) = viewModelScope.launch { store.delete(id) }
    fun setWifiOnly(on: Boolean) = viewModelScope.launch { settings.setMapsWifiOnly(on) }

    fun import(uri: Uri, displayName: String?) = viewModelScope.launch {
        transient.value = transient.value.copy(importing = true)
        val result = store.import(uri, displayName)
        transient.value = transient.value.copy(importing = false, error = if (result.isFailure) RegionError.CORRUPT else null)
    }

    fun consumeError() {
        transient.value = transient.value.copy(error = null)
    }
}
