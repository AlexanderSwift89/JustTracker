package com.justtracker.app.domain.stats

import com.justtracker.app.domain.stats.AccelerationEstimator.Companion.MAX_PLAUSIBLE_MPS2
import com.justtracker.app.domain.stats.AccelerationEstimator.Companion.MAX_SIGMA_MPS
import com.justtracker.app.domain.stats.AccelerationEstimator.Companion.MAX_SIGMA_MPS2
import com.justtracker.app.domain.stats.AccelerationEstimator.Companion.MIN_SIGMA_MPS
import com.justtracker.app.domain.stats.AccelerationEstimator.Companion.STATIONARY_MPS
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The acceleration formula shared by the live estimate ([AccelerationEstimator], a window behind the newest fix) and a
 * finished track's history (`TrackAcceleration`, a window centred on each point): the weighted least-squares slope of
 * the speed, its σ and the standing-still rule (docs/06_system_analysis.md §3.10, §3.11). One copy, so the two can
 * never drift apart.
 */
object AccelerationMath {
    /** One window's estimate: [mps2] clamped to ±[MAX_PLAUSIBLE_MPS2]; 0 when [stationary]. */
    class Fit(val mps2: Float, val sigmaMps2: Float, val stationary: Boolean)

    /**
     * Weight of a speed with the 68 % accuracy [sigmaMps]: 1/σ², the accuracy within [MIN_SIGMA_MPS]…[MAX_SIGMA_MPS] —
     * a receiver's pessimistic claim must not drop a usable speed (D-38); NaN counts as the ceiling.
     */
    fun weight(sigmaMps: Float): Float {
        val sigma = if (sigmaMps.isNaN()) MAX_SIGMA_MPS else sigmaMps.coerceIn(MIN_SIGMA_MPS, MAX_SIGMA_MPS)
        return 1f / (sigma * sigma)
    }

    /** A speed step of [dvMps] within [dtMs] (> 0) faster than [MAX_PLAUSIBLE_MPS2]: the receiver's glitch. */
    fun isOutlier(dvMps: Float, dtMs: Long): Boolean = abs(dvMps) * 1000f / dtMs > MAX_PLAUSIBLE_MPS2

    /**
     * Fits the [count] samples `time(i)`, `speed(i)`, `weight(i)`; times in ms, taken relative to [originMs] so the sums
     * stay small and exact. Null when the slope is undefined (all at one time) or its σ exceeds [MAX_SIGMA_MPS2]. The
     * caller checks the sample count and the span of the window.
     */
    inline fun fit(count: Int, originMs: Long, time: (Int) -> Long, speed: (Int) -> Float, weight: (Int) -> Float): Fit? {
        var sw = 0.0
        var st = 0.0
        var sv = 0.0
        var maxSpeed = 0f
        for (i in 0 until count) {
            val w = weight(i).toDouble()
            val v = speed(i)
            sw += w
            st += w * (time(i) - originMs) / 1000.0
            sv += w * v
            maxSpeed = max(maxSpeed, v)
        }
        val tMean = st / sw
        val vMean = sv / sw
        var stt = 0.0
        var stv = 0.0
        for (i in 0 until count) {
            val w = weight(i)
            val dt = (time(i) - originMs) / 1000.0 - tMean
            stt += w * dt * dt
            stv += w * dt * (speed(i) - vMean)
        }
        return slope(stt, stv, maxSpeed)
    }

    /** The end of [fit]: σ from the weighted spread of the times, then the standing-still rule, then the clamp. */
    fun slope(stt: Double, stv: Double, maxSpeed: Float): Fit? {
        if (stt <= 0.0) return null
        val sigma = sqrt(1.0 / stt).toFloat()
        if (sigma > MAX_SIGMA_MPS2) return null
        // The noise of a speed magnitude at a standstill is rectified: its slope means nothing.
        if (maxSpeed < STATIONARY_MPS) return Fit(0f, sigma, stationary = true)
        return Fit((stv / stt).toFloat().coerceIn(-MAX_PLAUSIBLE_MPS2, MAX_PLAUSIBLE_MPS2), sigma, stationary = false)
    }
}
