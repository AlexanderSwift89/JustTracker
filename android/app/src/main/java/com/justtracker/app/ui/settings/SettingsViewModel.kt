package com.justtracker.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justtracker.app.data.maps.MapModeController
import com.justtracker.app.data.maps.OfflineRegionStore
import com.justtracker.app.data.repo.SettingsRepository
import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.domain.maps.RegionStatus
import com.justtracker.app.domain.model.AppLanguage
import com.justtracker.app.domain.model.AppSettings
import com.justtracker.app.domain.model.ThemeMode
import com.justtracker.app.domain.model.UnitSystem
import com.justtracker.app.util.AppLocale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val mapModeController: MapModeController,
    regions: OfflineRegionStore,
) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val readyRegions = regions.regions
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
}
