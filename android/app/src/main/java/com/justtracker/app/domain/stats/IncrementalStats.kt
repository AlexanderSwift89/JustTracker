package com.justtracker.app.domain.stats

import com.justtracker.app.domain.geo.Sample

/**
 * Running totals kept by the recording service so the notification and the track row can be
 * updated without re-reading all points. Not persisted; the authoritative numbers are recomputed
 * by [TrackStatsCalculator] on finish.
 */
class IncrementalStats(
    var distanceM: Double = 0.0,
    var movingTimeMs: Long = 0,
    var maxSpeedMps: Double = 0.0,
    var pointCount: Int = 0,
) {
    var lastSample: Sample? = null
        private set
    private var smoothedSpeed = 0f

    /** Exponentially smoothed instantaneous speed (alpha 0.5). */
    val currentSpeedMps: Float get() = smoothedSpeed

    val avgSpeedMps: Double get() = if (movingTimeMs > 0) distanceM / (movingTimeMs / 1000.0) else 0.0

    fun accept(sample: Sample, distanceFromPrevM: Double, speedMps: Float) {
        val prev = lastSample
        if (prev != null) {
            distanceM += distanceFromPrevM
            val dt = sample.timestamp - prev.timestamp
            if (dt > 0 && distanceFromPrevM / (dt / 1000.0) > TrackStatsCalculator.MOVING_THRESHOLD_MPS) movingTimeMs += dt
        }
        smoothedSpeed = if (prev == null) speedMps else 0.5f * smoothedSpeed + 0.5f * speedMps
        if (smoothedSpeed > maxSpeedMps) maxSpeedMps = smoothedSpeed.toDouble()
        pointCount++
        lastSample = sample
    }

    /** Called on resume: the next point starts a new segment and must not connect to the previous one. */
    fun breakSegment() {
        lastSample = null
        smoothedSpeed = 0f
    }
}
