package com.justtracker.app.domain.stats

import com.justtracker.app.domain.geo.ElevationCalculator
import com.justtracker.app.domain.geo.Geo
import com.justtracker.app.domain.geo.effectiveSpeed
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.model.TrackStats

/**
 * Full statistics pass over an ordered list of points (docs/06_system_analysis.md §3.2–3.4).
 * Distances and moving time are only accumulated between neighbours of the same segment.
 */
object TrackStatsCalculator {
    /** Below this speed the user is considered standing still. */
    const val MOVING_THRESHOLD_MPS = 0.5f

    fun calculate(points: List<TrackPoint>, pausedTimeMs: Long, startedAt: Long, finishedAt: Long?): TrackStats {
        if (points.isEmpty()) return TrackStats.EMPTY
        var distance = 0.0
        var movingMs = 0L
        var maxSpeed = 0.0
        val speeds = smoothedSpeeds(points)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (a.segment != b.segment) continue
            val d = Geo.distanceMeters(a.lat, a.lon, b.lat, b.lon)
            distance += d
            val dt = b.timestamp - a.timestamp
            // Moving time is based on actual displacement so a receiver reporting speed=0 cannot zero it out.
            if (dt > 0 && d / (dt / 1000.0) > MOVING_THRESHOLD_MPS) movingMs += dt
            val v = speeds[i]
            if (v > maxSpeed) maxSpeed = v.toDouble()
        }
        val end = finishedAt ?: points.last().timestamp
        val total = (end - startedAt - pausedTimeMs).coerceAtLeast(0)
        val avg = if (movingMs > 0) distance / (movingMs / 1000.0) else 0.0
        val elevation = ElevationCalculator.gainLoss(points.mapNotNull { it.altitudeM })
        return TrackStats(
            distanceM = distance,
            movingTimeMs = movingMs,
            totalTimeMs = total,
            avgSpeedMps = avg,
            maxSpeedMps = maxSpeed,
            elevationGainM = elevation.gainM,
            elevationLossM = elevation.lossM,
            pointCount = points.size,
        )
    }

    /**
     * Per-point speed: reported GPS speed (see [effectiveSpeed]) or distance/time to the previous
     * point; then a 3-point median to suppress single spikes.
     */
    fun smoothedSpeeds(points: List<TrackPoint>): List<Float> {
        val raw = FloatArray(points.size)
        for (i in points.indices) {
            val p = points[i]
            val implied = if (i == 0 || points[i - 1].segment != p.segment) {
                0f
            } else {
                val q = points[i - 1]
                val dt = (p.timestamp - q.timestamp) / 1000.0
                if (dt <= 0) 0f else (Geo.distanceMeters(q.lat, q.lon, p.lat, p.lon) / dt).toFloat()
            }
            raw[i] = effectiveSpeed(p.speedMps, implied, MOVING_THRESHOLD_MPS)
        }
        return List(points.size) { i ->
            if (i == 0 || i == points.lastIndex) raw[i] else median3(raw[i - 1], raw[i], raw[i + 1])
        }
    }

    private fun median3(a: Float, b: Float, c: Float): Float = maxOf(minOf(a, b), minOf(maxOf(a, b), c))
}
