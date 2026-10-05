package com.justtracker.app.testing

import com.justtracker.app.data.db.ActivityAggregate
import com.justtracker.app.data.db.TrackDao
import com.justtracker.app.data.db.TrackEntity
import com.justtracker.app.data.db.TrackPointEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [TrackDao] for JVM tests: AUTOINCREMENT ids, CASCADE on track delete, and flows that re-emit on every
 * write like Room's table-level invalidation. [fullPointReads] counts `getPoints` calls.
 */
class FakeTrackDao : TrackDao {
    private val tracks = MutableStateFlow<Map<Long, TrackEntity>>(emptyMap())
    private val points = MutableStateFlow<List<TrackPointEntity>>(emptyList())
    private var nextTrackId = 1L
    private var nextPointId = 1L

    var fullPointReads = 0
        private set

    override suspend fun insertTrack(track: TrackEntity): Long {
        val id = nextTrackId++
        tracks.value = tracks.value + (id to track.copy(id = id))
        return id
    }

    override suspend fun updateTrack(track: TrackEntity) {
        if (track.id in tracks.value) tracks.value = tracks.value + (track.id to track)
    }

    override suspend fun getTrack(id: Long): TrackEntity? = tracks.value[id]

    override fun observeTrack(id: Long): Flow<TrackEntity?> = tracks.map { it[id] }

    private fun active(all: Map<Long, TrackEntity>) =
        all.values.filter { it.status == "RECORDING" || it.status == "PAUSED" }.maxByOrNull { it.startedAt }

    override suspend fun getActiveTrack(): TrackEntity? = active(tracks.value)

    override fun observeActiveTrack(): Flow<TrackEntity?> = tracks.map { active(it) }

    override fun observeFinishedTracks(): Flow<List<TrackEntity>> =
        tracks.map { all -> all.values.filter { it.status == "FINISHED" }.sortedByDescending { it.startedAt } }

    override fun observeActivityAggregates(): Flow<List<ActivityAggregate>> = tracks.map { all ->
        all.values.filter { it.status == "FINISHED" }.groupBy { it.activityType }
            .map { (type, l) -> ActivityAggregate(type, l.size, l.sumOf { it.distanceM }, l.sumOf { it.movingTimeMs }) }
            .sortedByDescending { it.distanceM }
    }

    override suspend fun deleteTrack(id: Long) {
        tracks.value = tracks.value - id
        points.value = points.value.filter { it.trackId != id }
    }

    override suspend fun renameTrack(id: Long, name: String) = edit(id) { it.copy(name = name) }

    override suspend fun setActivityType(id: Long, type: String, manual: Boolean) =
        edit(id) { it.copy(activityType = type, activityManual = manual) }

    override suspend fun setElevation(id: Long, gainM: Double, lossM: Double) =
        edit(id) { it.copy(elevationGainM = gainM, elevationLossM = lossM) }

    private fun edit(id: Long, change: (TrackEntity) -> TrackEntity) {
        val t = tracks.value[id] ?: return
        tracks.value = tracks.value + (id to change(t))
    }

    override suspend fun insertPoint(point: TrackPointEntity): Long {
        val id = nextPointId++
        points.value = points.value + point.copy(id = id)
        return id
    }

    private fun of(trackId: Long) = points.value.filter { it.trackId == trackId }

    override suspend fun getPoints(trackId: Long): List<TrackPointEntity> {
        fullPointReads++
        return of(trackId).sortedWith(compareBy({ it.timestamp }, { it.id }))
    }

    override suspend fun getPointsAfter(trackId: Long, afterId: Long): List<TrackPointEntity> =
        of(trackId).filter { it.id > afterId }.sortedBy { it.id }

    override suspend fun pointExists(id: Long): Boolean = points.value.any { it.id == id }

    override suspend fun maxSegment(trackId: Long): Int = of(trackId).maxOfOrNull { it.segment } ?: -1

    override suspend fun countSegmentPoints(trackId: Long, segment: Int): Int = of(trackId).count { it.segment == segment }

    override suspend fun deleteSegmentPoints(trackId: Long, segment: Int) {
        points.value = points.value.filterNot { it.trackId == trackId && it.segment == segment }
    }
}
