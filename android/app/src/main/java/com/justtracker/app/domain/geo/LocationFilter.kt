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

/** What the next fix says about the segment's pending first fix, see [LocationFilter.confirmStart]. */
enum class StartCheck {
    /** Consistent with the next fix: record the pending fix, then evaluate the next one as usual. */
    CONFIRMED,

    /** An impossible jump away from it: the pending fix was the outlier, the next fix takes its place. */
    REPLACE,

    /** The next fix says nothing (inaccurate or not newer): keep waiting. */
    IGNORE,
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
 * 5. speed: reported, or displacement / time ([effectiveSpeed])
 * 6. the first fix of a segment waits for the next one to confirm it ([confirmStart])
 * 7. a run of consistent fixes far from the recorded one moves the track there ([JumpStreak])
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

    /**
     * Rule 6 (docs/06_system_analysis.md §3.1): the first fix of a segment has nothing to be checked
     * against, so it is only recorded once the next fix does not contradict it. A far-off first fix (a
     * coarse network position, a stale location) would otherwise become the reference against which
     * every real fix is an implausible jump — the recording then stayed at 0 m for good.
     */
    fun confirmStart(pending: Sample, next: Sample): StartCheck = when (val r = evaluate(pending, next)) {
        is FilterResult.Accepted -> StartCheck.CONFIRMED
        is FilterResult.Rejected -> when (r.reason) {
            FilterResult.Reason.TOO_CLOSE -> StartCheck.CONFIRMED
            FilterResult.Reason.IMPLAUSIBLE_SPEED -> StartCheck.REPLACE
            FilterResult.Reason.INACCURATE, FilterResult.Reason.NOT_NEWER -> StartCheck.IGNORE
        }
    }

    /** True when [next] could follow [from]: accepted, or too close to count as movement. */
    fun consistent(from: Sample, next: Sample): Boolean = when (val r = evaluate(from, next)) {
        is FilterResult.Accepted -> true
        is FilterResult.Rejected -> r.reason == FilterResult.Reason.TOO_CLOSE
    }
}

/**
 * Rule 7 (docs/06_system_analysis.md §3.1): when [threshold] fixes in a row are an implausible jump away from
 * the last recorded fix but consistent with each other, that recorded fix was the outlier — a wrong position
 * repeated at the start (a stale or coarse fix, the emulator's previous location). Rejecting every later fix
 * against it kept the recording at 0 m for good; instead the track continues from the new position.
 */
class JumpStreak(private val filter: LocationFilter, private val threshold: Int = DEFAULT_THRESHOLD) {
    private var candidate: Sample? = null
    private var count = 0

    /** A fix was accepted or found consistent with the recorded one: the reference is fine. */
    fun reset() {
        candidate = null
        count = 0
    }

    /** [next] was rejected as an implausible jump; true when the track should continue from it. */
    fun onImplausible(next: Sample): Boolean {
        val previous = candidate
        count = if (previous != null && filter.consistent(previous, next)) count + 1 else 1
        candidate = next
        if (count < threshold) return false
        reset()
        return true
    }

    companion object {
        /** ≈ 5 s of fixes that agree with each other. */
        const val DEFAULT_THRESHOLD = 5
    }
}
