package com.justtracker.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.model.TrackStatus

@Entity(
    tableName = "tracks",
    indices = [Index("status"), Index("startedAt")],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val status: String,
    val activityType: String,
    val activityManual: Boolean,
    val startedAt: Long,
    val finishedAt: Long?,
    val distanceM: Double,
    val movingTimeMs: Long,
    val totalTimeMs: Long,
    val pausedTimeMs: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val elevationGainM: Double,
    val elevationLossM: Double,
    val pointCount: Int,
)

@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["trackId", "timestamp"])],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val segment: Int,
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val accuracyM: Float,
    val speedMps: Float?,
    val bearingDeg: Float?,
    /** Schema v2 (1.0.2, auto-migrated): vertical accuracy of [altitudeM]; null for older rows. */
    val verticalAccuracyM: Float? = null,
)

/** Aggregates per activity type for the Stats screen. */
data class ActivityAggregate(
    @ColumnInfo(name = "activityType") val activityType: String,
    @ColumnInfo(name = "count") val count: Int,
    @ColumnInfo(name = "distanceM") val distanceM: Double,
    @ColumnInfo(name = "movingTimeMs") val movingTimeMs: Long,
)

fun TrackEntity.toDomain() = Track(
    id = id,
    name = name,
    status = TrackStatus.valueOf(status),
    activityType = runCatching { ActivityType.valueOf(activityType) }.getOrDefault(ActivityType.UNKNOWN),
    activityManual = activityManual,
    startedAt = startedAt,
    finishedAt = finishedAt,
    distanceM = distanceM,
    movingTimeMs = movingTimeMs,
    totalTimeMs = totalTimeMs,
    pausedTimeMs = pausedTimeMs,
    avgSpeedMps = avgSpeedMps,
    maxSpeedMps = maxSpeedMps,
    elevationGainM = elevationGainM,
    elevationLossM = elevationLossM,
    pointCount = pointCount,
)

fun Track.toEntity() = TrackEntity(
    id = id,
    name = name,
    status = status.name,
    activityType = activityType.name,
    activityManual = activityManual,
    startedAt = startedAt,
    finishedAt = finishedAt,
    distanceM = distanceM,
    movingTimeMs = movingTimeMs,
    totalTimeMs = totalTimeMs,
    pausedTimeMs = pausedTimeMs,
    avgSpeedMps = avgSpeedMps,
    maxSpeedMps = maxSpeedMps,
    elevationGainM = elevationGainM,
    elevationLossM = elevationLossM,
    pointCount = pointCount,
)

fun TrackPointEntity.toDomain() = TrackPoint(
    id = id,
    trackId = trackId,
    segment = segment,
    timestamp = timestamp,
    lat = lat,
    lon = lon,
    altitudeM = altitudeM,
    accuracyM = accuracyM,
    speedMps = speedMps,
    bearingDeg = bearingDeg,
    verticalAccuracyM = verticalAccuracyM,
)

fun TrackPoint.toEntity() = TrackPointEntity(
    id = id,
    trackId = trackId,
    segment = segment,
    timestamp = timestamp,
    lat = lat,
    lon = lon,
    altitudeM = altitudeM,
    accuracyM = accuracyM,
    speedMps = speedMps,
    bearingDeg = bearingDeg,
    verticalAccuracyM = verticalAccuracyM,
)
