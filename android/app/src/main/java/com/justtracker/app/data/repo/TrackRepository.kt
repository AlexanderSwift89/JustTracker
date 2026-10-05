package com.justtracker.app.data.repo

import com.justtracker.app.data.db.ActivityAggregate
import com.justtracker.app.data.db.TrackDao
import com.justtracker.app.data.export.ExportFiles
import com.justtracker.app.di.AppDispatchers
import com.justtracker.app.data.db.toDomain
import com.justtracker.app.data.db.toEntity
import com.justtracker.app.domain.activity.ActivityClassifier
import com.justtracker.app.domain.geo.ElevationResult
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.model.TrackStatus
import com.justtracker.app.domain.stats.TrackStatsCalculator
import com.justtracker.app.util.traced
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single source of truth for tracks. The recording service writes here; every screen reads here.
 *
 * @param exportsDir folder of shared GPX copies ([ExportFiles]); a deleted track takes its copies with it.
 */
class TrackRepository(
    private val dao: TrackDao,
    private val exportsDir: File? = null,
    private val dispatchers: AppDispatchers = AppDispatchers(),
) {

    fun observeActiveTrack(): Flow<Track?> = dao.observeActiveTrack().map { it?.toDomain() }
    suspend fun getActiveTrack(): Track? = dao.getActiveTrack()?.toDomain()

    // Room re-runs a query on every write to its table — a recording writes `tracks` once a second. Equal results are
    // dropped here, so screens that show other tracks do not recompose for nothing (D-27).
    fun observeTrack(id: Long): Flow<Track?> = dao.observeTrack(id).distinctUntilChanged().map { it?.toDomain() }
    suspend fun getTrack(id: Long): Track? = dao.getTrack(id)?.toDomain()

    fun observeFinishedTracks(): Flow<List<Track>> = dao.observeFinishedTracks().distinctUntilChanged().map { l -> l.map { it.toDomain() } }
    fun observeFinishedTracksSince(since: Long): Flow<List<Track>> =
        dao.observeFinishedTracksSince(since).map { l -> l.map { it.toDomain() } }

    fun observeActivityAggregates(): Flow<List<ActivityAggregate>> = dao.observeActivityAggregates().distinctUntilChanged()

    fun observePoints(trackId: Long): Flow<List<TrackPoint>> = dao.observePoints(trackId).map { l -> l.map { it.toDomain() } }
    suspend fun getPoints(trackId: Long): List<TrackPoint> = dao.getPoints(trackId).map { it.toDomain() }

    /** Points of [trackId] after the point [lastKnownId] (0: all); null when that point was deleted since. */
    suspend fun pointsAfterIfIntact(trackId: Long, lastKnownId: Long): List<TrackPoint>? =
        dao.pointsAfterIfIntact(trackId, lastKnownId)?.map { it.toDomain() }
    suspend fun getLastPoint(trackId: Long): TrackPoint? = dao.getLastPoint(trackId)?.toDomain()
    suspend fun maxSegment(trackId: Long): Int = dao.maxSegment(trackId)

    /**
     * Deletes the points of [segment] when it has at most [maxPoints] of them — a stray start the recording
     * moved away from (LocationFilter rule 7); returns how many were deleted (0 for a real segment).
     */
    suspend fun deleteSegmentIfShort(trackId: Long, segment: Int, maxPoints: Int): Int {
        val count = dao.countSegmentPoints(trackId, segment)
        if (count == 0 || count > maxPoints) return 0
        dao.deleteSegmentPoints(trackId, segment)
        return count
    }

    suspend fun createTrack(name: String, startedAt: Long): Track {
        val track = Track(
            name = name,
            status = TrackStatus.RECORDING,
            activityType = ActivityType.UNKNOWN,
            activityManual = false,
            startedAt = startedAt,
            finishedAt = null,
        )
        val id = dao.insertTrack(track.toEntity())
        return track.copy(id = id)
    }

    suspend fun updateTrack(track: Track) = dao.updateTrack(track.toEntity())

    suspend fun addPoint(point: TrackPoint, updatedTrack: Track) =
        dao.insertPointAndUpdateTrack(point.toEntity(), updatedTrack.toEntity())

    suspend fun rename(id: Long, name: String) = dao.renameTrack(id, name.trim().take(MAX_NAME_LENGTH))

    suspend fun setActivityType(id: Long, type: ActivityType) = dao.setActivityType(id, type.name, manual = true)

    /** Stores gain/loss recomputed from the points (tracks finished before the 1.0.2 algorithm). */
    suspend fun setElevation(id: Long, elevation: ElevationResult) = dao.setElevation(id, elevation.gainM, elevation.lossM)

    /** Deletes the track, its points (foreign key CASCADE) and any GPX copy shared from it (SEC-11). */
    suspend fun delete(id: Long) {
        dao.deleteTrack(id)
        deleteExports(id)
    }

    private suspend fun deleteExports(id: Long) {
        val dir = exportsDir ?: return
        withContext(dispatchers.io) { ExportFiles.deleteForTrack(dir, id) }
    }

    /**
     * Finalises a recording: recomputes statistics from all points, classifies the activity
     * (unless the user picked one) and marks the track FINISHED. Returns null and deletes the
     * track when it has fewer than 2 points.
     */
    suspend fun finish(trackId: Long, finishedAt: Long, nameProvider: (ActivityType) -> String): Track? {
        val track = getTrack(trackId) ?: return null
        val points = getPoints(trackId)
        if (points.size < MIN_POINTS) {
            delete(trackId)
            return null
        }
        // Up to 100 000 points: the full pass runs off the caller's thread (the service calls this on the main thread
        // right when the user taps Stop), and the smoothed speeds are computed once for statistics and classification.
        val (stats, type) = withContext(dispatchers.default) {
            traced("track.finish") {
                val speeds = TrackStatsCalculator.smoothedSpeeds(points)
                val stats = TrackStatsCalculator.calculate(points, track.pausedTimeMs, track.startedAt, finishedAt, speeds)
                stats to if (track.activityManual) track.activityType else ActivityClassifier.classify(speeds)
            }
        }
        val finished = track.copy(
            status = TrackStatus.FINISHED,
            finishedAt = finishedAt,
            activityType = type,
            name = if (track.activityManual) track.name else nameProvider(type),
            distanceM = stats.distanceM,
            movingTimeMs = stats.movingTimeMs,
            totalTimeMs = stats.totalTimeMs,
            avgSpeedMps = stats.avgSpeedMps,
            maxSpeedMps = stats.maxSpeedMps,
            elevationGainM = stats.elevationGainM,
            elevationLossM = stats.elevationLossM,
            pointCount = stats.pointCount,
        )
        dao.updateTrack(finished.toEntity())
        return finished
    }

    companion object {
        const val MAX_NAME_LENGTH = 100
        const val MIN_POINTS = 2
    }
}
