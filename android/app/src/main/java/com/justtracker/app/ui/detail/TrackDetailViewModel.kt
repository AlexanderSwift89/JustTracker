package com.justtracker.app.ui.detail

import android.content.Intent
import com.justtracker.app.data.export.GpxExporter
import com.justtracker.app.data.repo.SettingsRepository
import com.justtracker.app.data.repo.TrackRepository
import com.justtracker.app.di.AppDispatchers
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justtracker.app.domain.geo.ElevationCalculator
import com.justtracker.app.domain.geo.ElevationResult
import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.TrackStatus
import com.justtracker.app.domain.model.UnitSystem
import com.justtracker.app.domain.track.TrackCursor
import com.justtracker.app.domain.track.TrackLine
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

data class TrackDetailUiState(
    val track: Track? = null,
    /** Track line with per-vertex speed, distance and time (docs/06_system_analysis.md §3.6). */
    val line: TrackLine = TrackLine.EMPTY,
    val units: UnitSystem = UnitSystem.METRIC,
    /** Explicit map source (US-22): the offline badge is shown in [MapMode.OFFLINE]. */
    val mapMode: MapMode = MapMode.ONLINE,
    val loaded: Boolean = false,
)

class TrackDetailViewModel(
    private val repo: TrackRepository,
    settings: SettingsRepository,
    private val gpxExporter: GpxExporter,
    private val trackId: Long,
    savedState: SavedStateHandle,
    dispatchers: AppDispatchers = AppDispatchers(),
) : ViewModel() {

    private class Geometry(val line: TrackLine, val elevation: ElevationResult)

    /**
     * Track line and gain/loss, derived from the points off the main thread; shared by the state, the cursor and the
     * write-back. A finished track's points never change: they are read once per view model. Observing them re-read
     * and rebuilt up to 100 000 points on every fix a recording stored meanwhile (D-27).
     */
    private val geometry: SharedFlow<Geometry> = flow { emit(repo.getPoints(trackId)) }
        .map { points -> Geometry(TrackLine.of(points), ElevationCalculator.gainLoss(points)) }
        .flowOn(dispatchers.default)
        .shareIn(viewModelScope, SharingStarted.Lazily, replay = 1)

    private val track = repo.observeTrack(trackId)

    /**
     * Global vertex index the user scrubbed to; 0 (track start) until touched. Saved state, like the
     * screen's dialogs and camera: returning to a process killed in the background keeps the position.
     */
    private val cursorIndex = savedState.getMutableStateFlow(KEY_CURSOR, 0)

    val state: StateFlow<TrackDetailUiState> = combine(
        track,
        geometry,
        settings.settings,
    ) { track, geo, prefs ->
        // Gain/loss are always shown as computed from the points by the current algorithm, never from a stale row.
        val shown = track?.copy(elevationGainM = geo.elevation.gainM, elevationLossM = geo.elevation.lossM)
        TrackDetailUiState(shown, geo.line, prefs.units, prefs.mapMode, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackDetailUiState())

    /**
     * Scrubber position (slider, tap, arrows); the start of the track until touched. A flow of its own: dragging the
     * slider redraws the cursor panel and moves the map's ring, not the whole screen.
     */
    val cursor: StateFlow<TrackCursor?> = combine(
        geometry,
        track.map { it?.startedAt }.distinctUntilChanged(),
        cursorIndex,
    ) { geo, startedAt, idx -> startedAt?.let { geo.line.cursorAt(idx, it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Tracks finished before 1.0.2 stored the gain/loss of the old algorithm (GPS noise counted as
        // climbing, docs/06_system_analysis.md §3.4): write the recomputed values back once.
        viewModelScope.launch {
            val track = repo.getTrack(trackId) ?: return@launch
            if (track.status != TrackStatus.FINISHED) return@launch
            val elevation = geometry.first().elevation
            if (abs(track.elevationGainM - elevation.gainM) > ELEVATION_EPSILON_M || abs(track.elevationLossM - elevation.lossM) > ELEVATION_EPSILON_M) {
                repo.setElevation(trackId, elevation)
            }
        }
    }

    /** Tap on the track line moves the scrubber to that vertex. */
    fun onTrackTap(index: Int) {
        cursorIndex.value = index
    }

    /** Slider: 0..1 share of the total distance. */
    fun scrubToFraction(fraction: Float) {
        cursorIndex.value = state.value.line.indexForFraction(fraction)
    }

    /** Arrow buttons: move one vertex back or forward. */
    fun stepCursor(delta: Int) {
        val count = state.value.line.size
        if (count == 0) return
        cursorIndex.update { (it + delta).coerceIn(0, count - 1) }
    }

    fun rename(name: String) = viewModelScope.launch { repo.rename(trackId, name) }
    fun setActivityType(type: ActivityType) = viewModelScope.launch { repo.setActivityType(trackId, type) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        repo.delete(trackId)
        onDone()
    }

    /** A share intent with the track as GPX, or null on failure ([GpxExporter]). */
    suspend fun shareIntent(): Intent? = gpxExporter.shareIntent(trackId)

    private companion object {
        /** Below this the stored gain/loss already match the recomputed ones (rounding only). */
        const val ELEVATION_EPSILON_M = 0.5
        const val KEY_CURSOR = "cursorIndex"
    }
}
