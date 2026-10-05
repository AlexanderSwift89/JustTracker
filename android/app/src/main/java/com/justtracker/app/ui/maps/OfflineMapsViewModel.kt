package com.justtracker.app.ui.maps

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justtracker.app.data.maps.CatalogRegion
import com.justtracker.app.data.maps.OfflineRegionStore
import com.justtracker.app.data.repo.SettingsRepository
import com.justtracker.app.di.AppDispatchers
import com.justtracker.app.domain.maps.OfflineRegion
import com.justtracker.app.domain.maps.RegionError
import com.justtracker.app.domain.maps.RegionSource
import com.justtracker.app.domain.model.AppLanguage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

class OfflineMapsViewModel(
    private val store: OfflineRegionStore,
    private val settings: SettingsRepository,
    dispatchers: AppDispatchers = AppDispatchers(),
) : ViewModel() {
    private val transient = MutableStateFlow(Transient())

    private data class Transient(val error: RegionError? = null, val importing: Boolean = false)

    /** The bundled catalogue, read once (it is cached per process as well). */
    private val catalog: Flow<List<CatalogRegion>> = flow { emit(store.catalog()) }

    /**
     * Free space of the maps folder: a StatFs call on the IO pool every few seconds, not on the main thread with every
     * progress tick of a download (P9).
     */
    private val freeBytes: Flow<Long> = flow {
        while (true) {
            emit(store.freeBytes())
            delay(FREE_SPACE_POLL_MS)
        }
    }.flowOn(dispatchers.io)

    val state: StateFlow<OfflineMapsUiState> = combine(
        store.regions,
        settings.settings,
        transient,
        catalog,
        freeBytes,
    ) { regions, prefs, t, catalog, free ->
        val byId = regions.associateBy { it.id }
        val language = prefs.language ?: AppLanguage.forDevice()
        OfflineMapsUiState(
            loaded = true,
            catalog = catalog.map { RegionRow(it, byId[it.id]) },
            imported = regions.filter { it.source == RegionSource.IMPORT }.map { RegionRow(null, it) }
                .sortedBy { it.name(language).lowercase() },
            wifiOnly = prefs.mapsWifiOnly,
            canDownload = store.canDownload,
            freeBytes = free,
            usedBytes = regions.sumOf { it.sizeBytes },
            language = language,
            error = t.error,
            importing = t.importing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineMapsUiState())

    fun download(id: String) = viewModelScope.launch {
        val error = store.download(id)
        if (error != null) transient.update { it.copy(error = error) }
    }

    fun cancel(id: String) = viewModelScope.launch { store.cancel(id) }
    fun delete(id: String) = viewModelScope.launch { store.delete(id) }
    fun setWifiOnly(on: Boolean) = viewModelScope.launch { settings.setMapsWifiOnly(on) }

    fun import(uri: Uri, displayName: String?) = viewModelScope.launch {
        transient.update { it.copy(importing = true) }
        val error = store.import(uri, displayName)
        transient.update { it.copy(importing = false, error = error) }
    }

    fun consumeError() = transient.update { it.copy(error = null) }

    private companion object {
        const val FREE_SPACE_POLL_MS = 5_000L
    }
}
