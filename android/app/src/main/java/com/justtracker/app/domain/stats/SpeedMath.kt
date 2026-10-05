package com.justtracker.app.domain.stats

/**
 * Speed rules shared by the full statistics pass ([TrackStatsCalculator]), the running totals of a recording
 * ([IncrementalStats]) and the live speed ([LiveMotion]), so the three can never drift apart
 * (docs/06_system_analysis.md §3.3).
 */
object SpeedMath {
    /** An interval counts as moving time when the displacement speed exceeds the moving threshold. */
    fun isMoving(distanceM: Double, dtMs: Long): Boolean =
        dtMs > 0 && distanceM / (dtMs / 1000.0) > TrackStatsCalculator.MOVING_THRESHOLD_MPS

    /** Average speed over the moving time; 0 before any movement. */
    fun average(distanceM: Double, movingTimeMs: Long): Double =
        if (movingTimeMs > 0) distanceM / (movingTimeMs / 1000.0) else 0.0

    /** Exponential smoothing with alpha 0.5 — of the stored points' speed and of the live Doppler speed. */
    fun smooth(previous: Float, next: Float): Float = 0.5f * previous + 0.5f * next
}
