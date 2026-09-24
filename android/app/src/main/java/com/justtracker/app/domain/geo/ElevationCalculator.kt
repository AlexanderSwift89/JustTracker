package com.justtracker.app.domain.geo

import com.justtracker.app.domain.model.TrackPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ElevationResult(val gainM: Double, val lossM: Double) {
    operator fun plus(other: ElevationResult) = ElevationResult(gainM + other.gainM, lossM + other.lossM)

    companion object {
        val ZERO = ElevationResult(0.0, 0.0)
    }
}

/**
 * Elevation gain and loss from GPS altitudes (docs/06_system_analysis.md §3.4).
 *
 * Phone GPS altitude is noisy (σ ≈ 3–10 m, the error persists for tens of seconds) and some receivers
 * hold a wrong value for a minute and then snap back by 10–20 m. Summing raw differences — or smoothing
 * over a handful of points — turned a flat 11 km run into "+134 m / −125 m". Every recording segment is
 * processed on its own, so the height difference across a pause or a recovery gap is never counted:
 * 1. quality gate: altitudes with a vertical accuracy worse than [MAX_VERTICAL_ACCURACY_M] are ignored,
 *    unless that would drop most of the segment (the receiver is then uniformly poor and all are used);
 * 2. spike removal: an excursion that starts with a physically impossible step (≥ [SPIKE_STEP_M] within
 *    [SPIKE_STEP_MAX_MS]) and returns to the level before the step within [SPIKE_RETURN_WINDOW_MS] is a
 *    receiver artefact and is dropped;
 * 3. smoothing along the path: mean altitude within ±[SMOOTHING_HALF_WINDOW_M] of travelled distance,
 *    widened to at least ±[SMOOTHING_MIN_HALF_WINDOW_MS] in time for fast movement — terrain height
 *    depends on the place, and standing still averages all its samples;
 * 4. turning-point hysteresis: a climb (descent) is counted from one turning point to the next once the
 *    profile has come back from its extremum by [THRESHOLD_M]; the unfinished last leg counts when it
 *    alone reaches the threshold.
 */
object ElevationCalculator {
    const val MAX_VERTICAL_ACCURACY_M = 15f
    const val SPIKE_STEP_M = 8.0
    const val SPIKE_STEP_MAX_MS = 3_000L
    const val SPIKE_RETURN_WINDOW_MS = 10 * 60_000L
    const val SMOOTHING_HALF_WINDOW_M = 75.0
    const val SMOOTHING_MIN_HALF_WINDOW_MS = 15_000L
    const val THRESHOLD_M = 6.0

    /** The return counts when it lands within max(3 m, 30 % of the step) of the level before the step. */
    private const val SPIKE_RETURN_MIN_TOLERANCE_M = 3.0
    private const val SPIKE_RETURN_TOLERANCE_SHARE = 0.3

    /** [points] ordered by time, as stored; segments are consecutive runs of the same segment number. */
    fun gainLoss(points: List<TrackPoint>): ElevationResult {
        var result = ElevationResult.ZERO
        var start = 0
        while (start < points.size) {
            var end = start + 1
            while (end < points.size && points[end].segment == points[start].segment) end++
            result += segmentGainLoss(points.subList(start, end))
            start = end
        }
        return result
    }

    private fun segmentGainLoss(points: List<TrackPoint>): ElevationResult {
        // Distance along the path; points without altitude still advance it.
        val along = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            along[i] = along[i - 1] + Geo.distanceMeters(a.lat, a.lon, b.lat, b.lon)
        }
        val withAltitude = points.indices.filter { points[it].altitudeM?.isFinite() == true }
        val accurate = withAltitude.filter { i ->
            val v = points[i].verticalAccuracyM
            v == null || v <= MAX_VERTICAL_ACCURACY_M
        }
        val used = if (accurate.size * 2 >= withAltitude.size) accurate else withAltitude
        if (used.size < 2) return ElevationResult.ZERO
        return profileGainLoss(
            times = LongArray(used.size) { points[used[it]].timestamp },
            distances = DoubleArray(used.size) { along[used[it]] },
            altitudes = DoubleArray(used.size) { points[used[it]].altitudeM!! },
        )
    }

    /** Steps 2–4 for one segment: parallel arrays ordered by time, distances non-decreasing. */
    internal fun profileGainLoss(times: LongArray, distances: DoubleArray, altitudes: DoubleArray): ElevationResult {
        val keep = spikeFreeMask(times, altitudes)
        val kept = keep.indices.filter { keep[it] }
        if (kept.size < 2) return ElevationResult.ZERO
        val smoothed = smoothAlongPath(
            times = LongArray(kept.size) { times[kept[it]] },
            distances = DoubleArray(kept.size) { distances[kept[it]] },
            altitudes = DoubleArray(kept.size) { altitudes[kept[it]] },
        )
        return turningPointGainLoss(smoothed, THRESHOLD_M)
    }

    /**
     * False for the samples of an excursion that begins with an impossible vertical step and comes back
     * to the level before it — e.g. 130 m → 150 m within a second, held for a minute, → 130 m. A step
     * that does not come back (a genuine catch-up of a slowly updated altitude) is kept.
     */
    internal fun spikeFreeMask(times: LongArray, altitudes: DoubleArray): BooleanArray {
        val keep = BooleanArray(altitudes.size) { true }
        var i = 1
        while (i < altitudes.size) {
            val step = altitudes[i] - altitudes[i - 1]
            if (abs(step) >= SPIKE_STEP_M && times[i] - times[i - 1] <= SPIKE_STEP_MAX_MS) {
                val base = altitudes[i - 1]
                val tolerance = max(SPIKE_RETURN_MIN_TOLERANCE_M, SPIKE_RETURN_TOLERANCE_SHARE * abs(step))
                var j = i + 1
                while (j < altitudes.size && times[j] - times[i] <= SPIKE_RETURN_WINDOW_MS && abs(altitudes[j] - base) > tolerance) j++
                if (j < altitudes.size && times[j] - times[i] <= SPIKE_RETURN_WINDOW_MS) {
                    for (k in i until j) keep[k] = false
                    i = j + 1
                    continue
                }
            }
            i++
        }
        return keep
    }

    /**
     * Mean altitude of the samples within ±[SMOOTHING_HALF_WINDOW_M] of travelled distance or
     * ±[SMOOTHING_MIN_HALF_WINDOW_MS] of time around each sample, whichever window is wider.
     * Both windows are contiguous around the sample, so their union is too.
     */
    internal fun smoothAlongPath(times: LongArray, distances: DoubleArray, altitudes: DoubleArray): DoubleArray {
        val n = altitudes.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + altitudes[i]
        val out = DoubleArray(n)
        var loD = 0
        var hiD = 0
        var loT = 0
        var hiT = 0
        for (i in 0 until n) {
            while (hiD < n && distances[hiD] - distances[i] <= SMOOTHING_HALF_WINDOW_M) hiD++
            while (distances[i] - distances[loD] > SMOOTHING_HALF_WINDOW_M) loD++
            while (hiT < n && times[hiT] - times[i] <= SMOOTHING_MIN_HALF_WINDOW_MS) hiT++
            while (times[i] - times[loT] > SMOOTHING_MIN_HALF_WINDOW_MS) loT++
            val lo = min(loD, loT)
            val hi = max(hiD, hiT)
            out[i] = (prefix[hi] - prefix[lo]) / (hi - lo)
        }
        return out
    }

    /**
     * Sums the legs between turning points of [profile]. A leg ends once the profile has come back from
     * the leg's extremum by [thresholdM]; until the first leg is known the lowest and highest values so
     * far are candidates for its start. The unfinished last leg counts when it alone reaches the threshold.
     */
    internal fun turningPointGainLoss(profile: DoubleArray, thresholdM: Double): ElevationResult {
        if (profile.size < 2) return ElevationResult.ZERO
        var gain = 0.0
        var loss = 0.0
        var lowest = profile[0]
        var highest = profile[0]
        var turn = profile[0]
        var extreme = profile[0]
        var direction = 0 // +1 climbing, −1 descending, 0 not known yet
        for (i in 1 until profile.size) {
            val h = profile[i]
            when {
                direction == 0 -> {
                    lowest = min(lowest, h)
                    highest = max(highest, h)
                    if (h - lowest >= thresholdM) {
                        direction = 1
                        turn = lowest
                        extreme = h
                    } else if (highest - h >= thresholdM) {
                        direction = -1
                        turn = highest
                        extreme = h
                    }
                }
                direction > 0 -> if (h > extreme) {
                    extreme = h
                } else if (extreme - h >= thresholdM) {
                    gain += extreme - turn
                    turn = extreme
                    extreme = h
                    direction = -1
                }
                else -> if (h < extreme) {
                    extreme = h
                } else if (h - extreme >= thresholdM) {
                    loss += turn - extreme
                    turn = extreme
                    extreme = h
                    direction = 1
                }
            }
        }
        if (direction > 0 && extreme - turn >= thresholdM) gain += extreme - turn
        if (direction < 0 && turn - extreme >= thresholdM) loss += turn - extreme
        return ElevationResult(gain, loss)
    }
}
