package io.treklog.app.ui.detail

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.treklog.app.di.AppContainer
import io.treklog.app.domain.gpx.GpxWriter
import io.treklog.app.domain.model.ActivityType
import io.treklog.app.domain.model.Track
import io.treklog.app.domain.model.TrackPoint
import io.treklog.app.domain.model.UnitSystem
import io.treklog.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.io.File

data class TrackDetailUiState(
    val track: Track? = null,
    val segments: List<List<GeoPoint>> = emptyList(),
    val units: UnitSystem = UnitSystem.METRIC,
    val loaded: Boolean = false,
)

class TrackDetailViewModel(private val container: AppContainer, private val trackId: Long) : ViewModel() {
    private val repo = container.trackRepository

    val state: StateFlow<TrackDetailUiState> = combine(
        repo.observeTrack(trackId),
        repo.observePoints(trackId),
        container.settingsRepository.settings,
    ) { track, points, settings ->
        TrackDetailUiState(track, toSegments(points), settings.units, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackDetailUiState())

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

    private fun toSegments(points: List<TrackPoint>): List<List<GeoPoint>> =
        points.groupBy { it.segment }.toSortedMap().values.map { seg -> seg.map { GeoPoint(it.lat, it.lon) } }
}
