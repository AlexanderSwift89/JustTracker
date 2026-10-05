package com.justtracker.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justtracker.app.data.repo.SettingsRepository
import com.justtracker.app.data.repo.TrackRepository
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.UnitSystem
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
