package com.justtracker.app.testing

import com.justtracker.app.data.repo.TrackRepository
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.model.TrackStatus

/** A finished track as History and Statistics see it. */
suspend fun TrackRepository.finishedTrack(
    name: String,
    startedAt: Long,
    distanceM: Double = 1000.0,
    type: ActivityType = ActivityType.WALK,
    avgSpeedMps: Double = 1.5,
): Track {
    val t = createTrack(name, startedAt)
    val finished = t.copy(
        status = TrackStatus.FINISHED, finishedAt = startedAt + 600_000, activityType = type,
        distanceM = distanceM, movingTimeMs = 600_000, avgSpeedMps = avgSpeedMps,
    )
    updateTrack(finished)
    return finished
}

/** [n] points 3 m apart heading north, one per second, on a flat 150 m. */
suspend fun TrackRepository.walk(track: Track, n: Int, from: Int = 0, segment: Int = 0) {
    var current = track
    for (i in from until from + n) {
        val p = TrackPoint(
            trackId = track.id, segment = segment, timestamp = track.startedAt + i * 1000L,
            lat = 55.0 + i * 3.0 / 111_195.0, lon = 37.0, altitudeM = 150.0, accuracyM = 5f, speedMps = 3f, bearingDeg = 0f,
        )
        current = current.copy(pointCount = current.pointCount + 1)
        addPoint(p, current)
    }
}
