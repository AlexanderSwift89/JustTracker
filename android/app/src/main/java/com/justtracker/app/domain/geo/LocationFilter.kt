package com.justtracker.app.domain.geo

import kotlin.math.max

/** Minimal location sample used by the filter; decoupled from android.location.Location. */
data class Sample(
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float?,
)

sealed class FilterResult {
    data class Accepted(val distanceM: Double, val speedMps: Float) : FilterResult()
    data class Rejected(val reason: Reason) : FilterResult()

    enum class Reason { INACCURATE, NOT_NEWER, TOO_CLOSE, IMPLAUSIBLE_SPEED }
}

/**
 * Reported GPS speed is preferred, except when the receiver claims "stationary" (below the moving
 * threshold) while the position clearly moved — some receivers and the emulator do this; then the
 * displacement speed is used.
 */
fun effectiveSpeed(reportedMps: Float?, impliedMps: Float, movingThreshold: Float = 0.5f): Float =
    if (reportedMps != null && (reportedMps > movingThreshold || impliedMps <= movingThreshold)) reportedMps else impliedMps

/**
 * Decides whether a new GPS sample should be recorded. Rules (docs/06_system_analysis.md §3.1):
 * 1. accuracy <= maxAccuracyM
 * 2. timestamp strictly increasing
 * 3. moved >= max(2 m, accuracy * 0.25) OR >= 30 s passed since last accepted point
 * 4. implied speed <= 70 m/s
 */
class LocationFilter(
    private val maxAccuracyM: Float = 50f,
    private val minDistanceM: Double = 2.0,
    private val stationaryIntervalMs: Long = 30_000,
    private val maxPlausibleSpeedMps: Double = 70.0,
) {
    fun evaluate(prev: Sample?, next: Sample): FilterResult {
        if (next.accuracyM > maxAccuracyM) return FilterResult.Rejected(FilterResult.Reason.INACCURATE)
        if (prev == null) return FilterResult.Accepted(0.0, next.speedMps ?: 0f)
        if (next.timestamp <= prev.timestamp) return FilterResult.Rejected(FilterResult.Reason.NOT_NEWER)

        val d = Geo.distanceMeters(prev.lat, prev.lon, next.lat, next.lon)
        val dtSec = (next.timestamp - prev.timestamp) / 1000.0
        val impliedSpeed = d / dtSec
        if (impliedSpeed > maxPlausibleSpeedMps) return FilterResult.Rejected(FilterResult.Reason.IMPLAUSIBLE_SPEED)

        val threshold = max(minDistanceM, next.accuracyM * 0.25)
        val elapsed = next.timestamp - prev.timestamp
        if (d < threshold && elapsed < stationaryIntervalMs) return FilterResult.Rejected(FilterResult.Reason.TOO_CLOSE)

        return FilterResult.Accepted(d, effectiveSpeed(next.speedMps, impliedSpeed.toFloat()))
    }
}
