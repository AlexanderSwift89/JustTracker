package com.justtracker.app.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justtracker.app.data.repo.LiveTrackLine
import com.justtracker.app.data.location.LocationSource
import com.justtracker.app.data.repo.SettingsRepository
import com.justtracker.app.data.repo.TrackRepository
import com.justtracker.app.di.AppDispatchers
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.TrackStatus
import com.justtracker.app.domain.model.UnitSystem
import com.justtracker.app.domain.stats.Acceleration
import com.justtracker.app.service.LiveTrackingState
import com.justtracker.app.service.TrackingControl
import com.justtracker.app.domain.geo.LatLon
import com.justtracker.app.domain.track.TrackLine
import com.justtracker.app.domain.track.TrackTapInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RecordStatus { IDLE, RECORDING, PAUSED }

data class RecordUiState(
    val track: Track? = null,
    /** Track line with per-vertex speed (docs/06_system_analysis.md §3.6). */
    val line: TrackLine = TrackLine.EMPTY,
    val live: LiveTrackingState = LiveTrackingState(),
    /** Last position good enough for the marker; a value, so an unchanged position does not redraw the map (D-28). */
    val position: LatLon? = null,
    val units: UnitSystem = UnitSystem.METRIC,
    val keepScreenOn: Boolean = false,
    val nowMs: Long = System.currentTimeMillis(),
    /** Section of the line the user tapped, if any. */
    val tapped: TrackTapInfo? = null,
    /** The acceleration indicator is open under the speed (US-23). */
    val showAcceleration: Boolean = false,
    /** The one-time hint "tap the speed to see acceleration" is still to be shown. */
    val accelerationHintPending: Boolean = false,
) {
    /** Primary time: from Start until now, pauses included (US-06). */
    val recordingTimeMs: Long
        get() = track?.recordingTimeMs(nowMs) ?: 0L

    val status: RecordStatus
        get() = when (track?.status) {
            TrackStatus.RECORDING -> RecordStatus.RECORDING
            TrackStatus.PAUSED -> RecordStatus.PAUSED
            else -> RecordStatus.IDLE
        }

    /** No fix for 10 s while recording → "searching GPS" (US-06). */
    val gpsSearching: Boolean
        get() = status == RecordStatus.RECORDING && nowMs - live.lastFixAt > GPS_STALE_MS

    /** Active track exists in DB but no service is alive in this process → offer recovery (US-04). */
    val needsRecovery: Boolean
        get() = track != null && !live.serviceRunning

    /** Live acceleration while recording; null when unknown, paused or not confirmed by a fix for [ACCELERATION_STALE_MS] (ADR-20). */
    val acceleration: Acceleration?
        get() = live.acceleration?.takeIf { status == RecordStatus.RECORDING && nowMs - live.accelerationAt <= ACCELERATION_STALE_MS }

    companion object {
        const val GPS_STALE_MS = 10_000L
        const val ACCELERATION_STALE_MS = 3_000L
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecordViewModel(
    tracks: TrackRepository,
    private val settings: SettingsRepository,
    private val tracking: TrackingControl,
    private val location: LocationSource,
    dispatchers: AppDispatchers = AppDispatchers(),
) : ViewModel() {
    // One Room query for the state and the line: the row of the active track changes with every stored fix.
    private val activeTrack = tracks.observeActiveTrack()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** Lives as long as the view model: coming back to the tab continues from the tail, without a full read. */
    private val liveLine = LiveTrackLine(tracks)

    /**
     * The recording clock: once a second while a track is recording or paused (time, "searching GPS", stale
     * acceleration), a single value otherwise — an idle Record screen is not recomposed and redrawn every second (D-28).
     */
    private val clock: Flow<Long> = activeTrack
        .map { it?.status == TrackStatus.RECORDING || it?.status == TrackStatus.PAUSED }
        .distinctUntilChanged()
        .flatMapLatest { running ->
            if (!running) {
                flowOf(System.currentTimeMillis())
            } else {
                flow {
                    while (true) {
                        emit(System.currentTimeMillis())
                        delay(TICK_MS)
                    }
                }
            }
        }

    // Speed smoothing + distances are O(n) per emission (1 Hz): keep them off the main thread.
    // Every stored fix rewrites the track row: read only the new points then (ADR-25). conflate + map, not mapLatest:
    // an update is never cancelled halfway, a slow one just skips to the newest row.
    private val line = activeTrack
        .conflate()
        .map { track -> liveLine.update(track) }
        .flowOn(dispatchers.default)

    private val tapped = MutableStateFlow<TrackTapInfo?>(null)

    val state: StateFlow<RecordUiState> = combine(
        combine(activeTrack, line, tapped) { t, l, tap -> Sources(t, l, tap) },
        tracking.live,
        settings.settings,
        clock,
    ) { src, live, prefs, now ->
        RecordUiState(
            track = src.track,
            line = src.line,
            live = live,
            position = live.lastLat?.let { lat -> live.lastLon?.let { lon -> LatLon(lat, lon) } },
            units = prefs.units,
            keepScreenOn = prefs.keepScreenOn,
            nowMs = now,
            // A tap belongs to the track it was made on; drop it once that track is finished.
            tapped = if (src.track == null) null else src.tapped,
            showAcceleration = prefs.showAcceleration,
            accelerationHintPending = !prefs.accelerationHintShown && !prefs.showAcceleration,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordUiState())

    private class Sources(val track: Track?, val line: TrackLine, val tapped: TrackTapInfo?)

    private companion object {
        const val TICK_MS = 1_000L
    }

    fun start() = tracking.start()
    fun pause() = tracking.pause()
    fun resume() = tracking.resume()
    fun stop() = tracking.stop()
    fun recover() = tracking.recover()

    /** Tap on the track line: resolve the vertex to its speed / distance / elapsed time. */
    fun onTrackTap(index: Int) {
        val s = state.value
        val startedAt = s.track?.startedAt ?: return
        tapped.value = s.line.tapInfo(index, startedAt)
    }

    fun dismissTap() = tapped.update { null }

    /** Tap on the speed: shows or hides the acceleration indicator; the choice is kept between recordings. */
    fun toggleAcceleration() {
        val show = !state.value.showAcceleration
        viewModelScope.launch { settings.setShowAcceleration(show) }
    }

    fun onAccelerationHintShown() {
        viewModelScope.launch { settings.setAccelerationHintShown() }
    }

    /** Seeds the position marker from the last known location so the map opens near the user ([TrackingControl.seedPosition]). */
    fun seedLastKnownLocation() {
        viewModelScope.launch {
            val loc = location.lastKnown() ?: return@launch
            tracking.seedPosition(loc.latitude, loc.longitude)
        }
    }
}
