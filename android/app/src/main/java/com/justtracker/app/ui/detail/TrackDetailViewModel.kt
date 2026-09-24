package com.justtracker.app.ui.detail

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.justtracker.app.di.AppContainer
import com.justtracker.app.domain.geo.ElevationCalculator
import com.justtracker.app.domain.geo.ElevationResult
import com.justtracker.app.domain.gpx.GpxWriter
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.TrackStatus
import com.justtracker.app.domain.model.UnitSystem
import com.justtracker.app.data.poi.PoiResult
import com.justtracker.app.domain.poi.Poi
import com.justtracker.app.ui.common.PathSegment
import com.justtracker.app.ui.common.TrackCursor
import com.justtracker.app.ui.common.TrackPath
import com.justtracker.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

data class TrackDetailUiState(
    val track: Track? = null,
    /** Track line with per-vertex speed, distance and time (docs/06_system_analysis.md §3.6). */
    val segments: List<PathSegment> = emptyList(),
    val units: UnitSystem = UnitSystem.METRIC,
    val loaded: Boolean = false,
    /** Places with a Wikipedia article within 400 m of the track; empty when disabled/offline. */
    val pois: List<Poi> = emptyList(),
    /** Scrubber position on the track (slider / tap / arrows); start of the track by default. */
    val cursor: TrackCursor? = null,
)

class TrackDetailViewModel(
    private val container: AppContainer,
    private val trackId: Long,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val repo = container.trackRepository

    /**
     * Places along the track. Starts with an empty list *immediately* (`scan`) so the screen never
     * waits for Overpass: the lookup is rate-limited (≥ 15 s between calls, 60 s backoff) and used to
     * hold back the whole state — the track appeared only after the request finished (D-11). An
     * unavailable result keeps whatever was shown before.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pois: Flow<List<Poi>> = combine(repo.observePoints(trackId), container.settingsRepository.settings) { points, s ->
        if (s.poiEnabled && points.size >= 2) points.map { it.lat to it.lon } else emptyList()
    }
        .distinctUntilChanged()
        .mapLatest { line ->
            if (line.isEmpty()) emptyList()
            else when (val r = container.poiRepository.aroundTrack(trackId, line)) {
                is PoiResult.Found -> r.pois
                PoiResult.Unavailable -> null
            }
        }
        .scan(emptyList<Poi>()) { prev, next -> next ?: prev }

    private class Geometry(val segments: List<PathSegment>, val elevation: ElevationResult)

    /** Track line and gain/loss, both derived from the points off the main thread; shared by the state and the write-back. */
    private val geometry: SharedFlow<Geometry> = repo.observePoints(trackId)
        .map { points -> Geometry(TrackPath.build(points), ElevationCalculator.gainLoss(points)) }
        .flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * Global vertex index the user scrubbed to; 0 (track start) until touched. Saved state, like the
     * screen's dialogs and camera: returning to a process killed in the background keeps the position.
     */
    private val cursorIndex = savedState.getMutableStateFlow(KEY_CURSOR, 0)

    val state: StateFlow<TrackDetailUiState> = combine(
        repo.observeTrack(trackId),
        geometry,
        container.settingsRepository.settings,
        pois,
        cursorIndex,
    ) { track, geo, settings, poiList, idx ->
        // Gain/loss are always shown as computed from the points by the current algorithm, never from a stale row.
        val shown = track?.copy(elevationGainM = geo.elevation.gainM, elevationLossM = geo.elevation.lossM)
        val cursor = shown?.let { TrackPath.cursorAt(geo.segments, idx, it.startedAt) }
        TrackDetailUiState(shown, geo.segments, settings.units, loaded = true, pois = poiList, cursor = cursor)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackDetailUiState())

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
    fun onTrackTap(segment: Int, index: Int) {
        cursorIndex.value = TrackPath.globalIndex(state.value.segments, segment, index)
    }

    /** Slider: 0..1 share of the total distance. */
    fun scrubToFraction(fraction: Float) {
        cursorIndex.value = TrackPath.indexForFraction(state.value.segments, fraction)
    }

    /** Arrow buttons: move one vertex back or forward. */
    fun stepCursor(delta: Int) {
        val count = TrackPath.pointCount(state.value.segments)
        if (count == 0) return
        cursorIndex.update { (it + delta).coerceIn(0, count - 1) }
    }

    fun rename(name: String) = viewModelScope.launch { repo.rename(trackId, name) }
    fun setActivityType(type: ActivityType) = viewModelScope.launch { repo.setActivityType(trackId, type) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        repo.delete(trackId)
        onDone()
    }

    /**
     * Writes the GPX into cacheDir/exports and returns a share intent, or null on failure.
     * Files older than 24 h are purged on every export (docs/06_system_analysis.md UC-04).
     */
    suspend fun buildShareIntent(context: Context): Intent? = withContext(Dispatchers.IO) {
        val track = repo.getTrack(trackId) ?: return@withContext null
        val points = repo.getPoints(trackId)
        try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
            val file = File(dir, GpxWriter.fileName(track))
            file.bufferedWriter().use { GpxWriter.write(track, points, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, track.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            AppLog.e("GPX export failed", e)
            null
        }
    }

    private companion object {
        /** Below this the stored gain/loss already match the recomputed ones (rounding only). */
        const val ELEVATION_EPSILON_M = 0.5
        const val KEY_CURSOR = "cursorIndex"
    }
}
