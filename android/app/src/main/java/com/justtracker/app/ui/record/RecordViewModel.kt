package com.justtracker.app.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justtracker.app.di.AppContainer
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.TrackStatus
import com.justtracker.app.domain.model.UnitSystem
import com.justtracker.app.domain.stats.Acceleration
import com.justtracker.app.service.LiveTrackingState
import com.justtracker.app.ui.common.PathSegment
import com.justtracker.app.ui.common.TrackPath
import com.justtracker.app.ui.common.TrackTapInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

enum class RecordStatus { IDLE, RECORDING, PAUSED }

data class RecordUiState(
    val track: Track? = null,
    /** Track line with per-vertex speed (docs/06_system_analysis.md §3.6). */
    val segments: List<PathSegment> = emptyList(),
    val live: LiveTrackingState = LiveTrackingState(),
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

    val position: GeoPoint?
        get() = live.lastLat?.let { lat -> live.lastLon?.let { lon -> GeoPoint(lat, lon) } }

    /** Live acceleration while recording; null when unknown, paused or not confirmed by a fix for [ACCELERATION_STALE_MS] (ADR-20). */
    val acceleration: Acceleration?
        get() = live.acceleration?.takeIf { status == RecordStatus.RECORDING && nowMs - live.accelerationAt <= ACCELERATION_STALE_MS }

    companion object {
        const val GPS_STALE_MS = 10_000L
        const val ACCELERATION_STALE_MS = 3_000L
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecordViewModel(private val container: AppContainer) : ViewModel() {
    private val controller = container.trackingController

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            kotlinx.coroutines.delay(1000)
        }
    }

    private val activeTrack = container.trackRepository.observeActiveTrack()

    // Speed smoothing + distances are O(n) per emission (1 Hz): keep them off the main thread.
    private val segments = activeTrack
        .flatMapLatest { track -> if (track == null) flowOf(emptyList()) else container.trackRepository.observePoints(track.id) }
        .map { points -> TrackPath.build(points) }
        .flowOn(Dispatchers.Default)

    private val tapped = MutableStateFlow<TrackTapInfo?>(null)

    val state: StateFlow<RecordUiState> = combine(
        combine(activeTrack, segments, tapped) { t, s, tap -> Sources(t, s, tap) },
        controller.live,
        container.settingsRepository.settings,
        ticker,
    ) { src, live, settings, now ->
        RecordUiState(
            track = src.track,
            segments = src.segments,
            live = live,
            units = settings.units,
            keepScreenOn = settings.keepScreenOn,
            nowMs = now,
            // A tap belongs to the track it was made on; drop it once that track is finished.
            tapped = if (src.track == null) null else src.tapped,
            showAcceleration = settings.showAcceleration,
            accelerationHintPending = !settings.accelerationHintShown && !settings.showAcceleration,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordUiState())

    private class Sources(val track: Track?, val segments: List<PathSegment>, val tapped: TrackTapInfo?)

    fun start() = controller.start()
    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun stop() = controller.stop()
    fun recover() = controller.recover()

    /** Tap on the track line: resolve the vertex to its speed / distance / elapsed time. */
    fun onTrackTap(segment: Int, index: Int) {
        val s = state.value
        val startedAt = s.track?.startedAt ?: return
        tapped.value = TrackPath.tapInfo(s.segments, segment, index, startedAt)
    }

    fun dismissTap() = tapped.update { null }

    /** Tap on the speed: shows or hides the acceleration indicator; the choice is kept between recordings. */
    fun toggleAcceleration() {
        val show = !state.value.showAcceleration
        viewModelScope.launch { container.settingsRepository.setShowAcceleration(show) }
    }

    fun onAccelerationHintShown() {
        viewModelScope.launch { container.settingsRepository.setAccelerationHintShown() }
    }

    /**
     * Seeds the position marker from the last known location so the map opens near the user. The position never
     * leaves the device: map tiles are the only network traffic tied to the viewed area (ADR-24).
     */
    fun seedLastKnownLocation() {
        viewModelScope.launch {
            val loc = container.locationSource.lastKnown() ?: return@launch
            controller.update { live ->
                if (live.lastLat == null) live.copy(lastLat = loc.latitude, lastLon = loc.longitude) else live
            }
        }
    }
}
