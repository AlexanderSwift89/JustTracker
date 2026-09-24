package com.justtracker.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Insert
    suspend fun insertTrack(track: TrackEntity): Long

    @Update
    suspend fun updateTrack(track: TrackEntity)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrack(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observeTrack(id: Long): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE status IN ('RECORDING', 'PAUSED') ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveTrack(): TrackEntity?

    @Query("SELECT * FROM tracks WHERE status IN ('RECORDING', 'PAUSED') ORDER BY startedAt DESC LIMIT 1")
    fun observeActiveTrack(): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE status = 'FINISHED' ORDER BY startedAt DESC")
    fun observeFinishedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE status = 'FINISHED' AND startedAt >= :since ORDER BY startedAt DESC")
    fun observeFinishedTracksSince(since: Long): Flow<List<TrackEntity>>

    @Query(
        "SELECT activityType, COUNT(*) AS count, SUM(distanceM) AS distanceM, SUM(movingTimeMs) AS movingTimeMs " +
            "FROM tracks WHERE status = 'FINISHED' GROUP BY activityType ORDER BY distanceM DESC",
    )
    fun observeActivityAggregates(): Flow<List<ActivityAggregate>>

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrack(id: Long)

    @Query("UPDATE tracks SET name = :name WHERE id = :id")
    suspend fun renameTrack(id: Long, name: String)

    @Query("UPDATE tracks SET activityType = :type, activityManual = :manual WHERE id = :id")
    suspend fun setActivityType(id: Long, type: String, manual: Boolean)

    @Query("UPDATE tracks SET elevationGainM = :gainM, elevationLossM = :lossM WHERE id = :id")
    suspend fun setElevation(id: Long, gainM: Double, lossM: Double)

    @Insert
    suspend fun insertPoint(point: TrackPointEntity): Long

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp ASC")
    suspend fun getPoints(trackId: Long): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp ASC")
    fun observePoints(trackId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastPoint(trackId: Long): TrackPointEntity?

    @Query("SELECT COUNT(*) FROM track_points WHERE trackId = :trackId")
    suspend fun countPoints(trackId: Long): Int

    @Query("SELECT COALESCE(MAX(segment), -1) FROM track_points WHERE trackId = :trackId")
    suspend fun maxSegment(trackId: Long): Int

    @Query("SELECT COUNT(*) FROM track_points WHERE trackId = :trackId AND segment = :segment")
    suspend fun countSegmentPoints(trackId: Long, segment: Int): Int

    @Query("DELETE FROM track_points WHERE trackId = :trackId AND segment = :segment")
    suspend fun deleteSegmentPoints(trackId: Long, segment: Int)

    @Transaction
    suspend fun insertPointAndUpdateTrack(point: TrackPointEntity, track: TrackEntity) {
        insertPoint(point)
        updateTrack(track)
    }
}
