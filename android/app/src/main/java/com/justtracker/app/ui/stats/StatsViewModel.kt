package com.justtracker.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justtracker.app.data.repo.SettingsRepository
import com.justtracker.app.data.repo.TrackRepository
import com.justtracker.app.domain.model.UnitSystem
import com.justtracker.app.domain.stats.ActivityTotals
import com.justtracker.app.domain.stats.TrackSummaries
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class StatsUiState(
    val loaded: Boolean = false,
    val summaries: TrackSummaries = TrackSummaries(),
    val byType: List<ActivityTotals> = emptyList(),
    val units: UnitSystem = UnitSystem.METRIC,
)

class StatsViewModel(
    repo: TrackRepository,
    settings: SettingsRepository,
    private val currentZone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {
    val state = combine(
        repo.observeFinishedTracks(),
        repo.observeActivityTotals(),
        settings.settings,
    ) { tracks, byType, prefs ->
        val zone = currentZone()
        StatsUiState(loaded = true, summaries = TrackSummaries.of(tracks, LocalDate.now(zone), zone), byType = byType, units = prefs.units)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())
}
