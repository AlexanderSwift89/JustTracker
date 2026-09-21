package com.justtracker.app.data.maps

import com.justtracker.app.data.network.ConnectivityObserver
import com.justtracker.app.data.repo.SettingsRepository
import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.domain.maps.MapModeAdvisor
import com.justtracker.app.domain.maps.MapModeSuggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Turns connectivity changes into an explicit question for the user (US-22): the map mode itself is
 * only ever changed through [setMode] — from the Settings dialog or the confirmation prompt.
 */
class MapModeController(
    private val settings: SettingsRepository,
    connectivity: ConnectivityObserver,
    regions: OfflineRegionStore,
    scope: CoroutineScope,
) {
    /** Connectivity state the user declined to switch for; cleared when connectivity changes again. */
    private val declinedFor = MutableStateFlow<Boolean?>(null)

    val online: StateFlow<Boolean> = connectivity.online

    val mode: StateFlow<MapMode> = settings.settings.map { it.mapMode }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, MapMode.ONLINE)

    /** Non-null while the app should show the "switch map mode?" prompt. */
    val suggestion: StateFlow<MapModeSuggestion?> = combine(
        mode,
        connectivity.online,
        regions.readyCoverage.map { it.isNotEmpty() }.distinctUntilChanged(),
        declinedFor,
    ) { mode, online, hasRegions, declined ->
        MapModeAdvisor.suggest(mode, online, hasRegions, declined)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun setMode(mode: MapMode) {
        declinedFor.value = null
        settings.setMapMode(mode)
    }

    /** User chose to keep the current mode for the current connectivity state. */
    fun dismissSuggestion() {
        declinedFor.value = online.value
    }
}
