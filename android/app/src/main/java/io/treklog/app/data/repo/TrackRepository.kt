package io.treklog.app.data.repo

import io.treklog.app.data.db.ActivityAggregate
import io.treklog.app.data.db.TrackDao
import io.treklog.app.data.db.toDomain
import io.treklog.app.data.db.toEntity
import io.treklog.app.domain.activity.ActivityClassifier
import io.treklog.app.domain.model.ActivityType
import io.treklog.app.domain.model.Track
import io.treklog.app.domain.model.TrackPoint
import io.treklog.app.domain.model.TrackStatus
import io.treklog.app.domain.stats.TrackStatsCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for tracks. The recording service writes here; every screen reads here.
 */
class TrackRepository(private val dao: TrackDao) {

    fun observeActiveTrack(): Flow<Track?> = dao.observeActiveTrack().map { it?.toDomain() }
    suspend fun getActiveTrack(): Track? = dao.getActiveTrack()?.toDomain()

    fun observeTrack(id: Long): Flow<Track?> = dao.observeTrack(id).map { it?.toDomain() }
    suspend fun getTrack(id: Long): Track? = dao.getTrack(id)?.toDomain()

    fun observeFinishedTracks(): Flow<List<Track>> = dao.observeFinishedTracks().map { l -> l.map { it.toDomain() } }
    fun observeFinishedTracksSince(since: Long): Flow<List<Track>> =
        dao.observeFinishedTracksSince(since).map { l -> l.map { it.toDomain() } }

    fun observeActivityAggregates(): Flow<List<ActivityAggregate>> = dao.observeActivityAggregates()

    fun observePoints(trackId: Long): Flow<List<TrackPoint>> = dao.observePoints(trackId).map { l -> l.map { it.toDomain() } }
    suspend fun getPoints(trackId: Long): List<TrackPoint> = dao.getPoints(trackId).map { it.toDomain() }
    suspend fun getLastPoint(trackId: Long): TrackPoint? = dao.getLastPoint(trackId)?.toDomain()
    suspend fun maxSegment(trackId: Long): Int = dao.maxSegment(trackId)

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

    suspend fun delete(id: Long) = dao.deleteTrack(id)

    /**
     * Finalises a recording: recomputes statistics from all points, classifies the activity
     * (unless the user picked one) and marks the track FINISHED. Returns null and deletes the
     * track when it has fewer than 2 points.
     */
    suspend fun finish(trackId: Long, finishedAt: Long, nameProvider: (ActivityType) -> String): Track? {
        val track = getTrack(trackId) ?: return null
        val points = getPoints(trackId)
        if (points.size < MIN_POINTS) {
            dao.deleteTrack(trackId)
            return null
        }
        val stats = TrackStatsCalculator.calculate(points, track.pausedTimeMs, track.startedAt, finishedAt)
        val type = if (track.activityManual) {
            track.activityType
        } else {
            ActivityClassifier.classify(TrackStatsCalculator.smoothedSpeeds(points))
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
