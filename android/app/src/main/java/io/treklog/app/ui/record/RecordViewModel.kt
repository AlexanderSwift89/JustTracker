package io.treklog.app.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.treklog.app.di.AppContainer
import io.treklog.app.domain.model.Track
import io.treklog.app.domain.model.TrackPoint
import io.treklog.app.domain.model.TrackStatus
import io.treklog.app.domain.model.UnitSystem
import io.treklog.app.service.LiveTrackingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

enum class RecordStatus { IDLE, RECORDING, PAUSED }

data class RecordUiState(
    val track: Track? = null,
    val segments: List<List<GeoPoint>> = emptyList(),
    val live: LiveTrackingState = LiveTrackingState(),
    val units: UnitSystem = UnitSystem.METRIC,
    val keepScreenOn: Boolean = false,
    val nowMs: Long = System.currentTimeMillis(),
) {
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

    companion object {
        const val GPS_STALE_MS = 10_000L
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

    private val segments = activeTrack
        .flatMapLatest { track -> if (track == null) flowOf(emptyList()) else container.trackRepository.observePoints(track.id) }
        .flatMapLatest { points -> flowOf(toSegments(points)) }

    val state: StateFlow<RecordUiState> = combine(
        activeTrack,
        segments,
        controller.live,
        container.settingsRepository.settings,
        ticker,
    ) { track, segs, live, settings, now ->
        RecordUiState(
            track = track,
            segments = segs,
            live = live,
            units = settings.units,
            keepScreenOn = settings.keepScreenOn,
            nowMs = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordUiState())

    fun start() = controller.start()
    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun stop() = controller.stop()
    fun recover() = controller.recover()

    /** Seeds the position marker from the last known location so the map opens near the user. */
    fun seedLastKnownLocation() {
        viewModelScope.launch {
            val loc = container.locationSource.lastKnown() ?: return@launch
            controller.update { live ->
                if (live.lastLat == null) live.copy(lastLat = loc.latitude, lastLon = loc.longitude) else live
            }
        }
    }

    private fun toSegments(points: List<TrackPoint>): List<List<GeoPoint>> =
        points.groupBy { it.segment }.toSortedMap().values.map { seg -> seg.map { GeoPoint(it.lat, it.lon) } }
}
