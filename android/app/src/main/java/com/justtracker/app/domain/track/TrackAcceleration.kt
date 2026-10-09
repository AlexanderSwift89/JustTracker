package com.justtracker.app.domain.track

import com.justtracker.app.domain.geo.Geo
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.stats.AccelerationEstimator
import com.justtracker.app.domain.stats.AccelerationMath
import com.justtracker.app.domain.stats.AccelerationState
import com.justtracker.app.domain.stats.LiveMotion
import com.justtracker.app.domain.stats.SpeedMath
import com.justtracker.app.domain.stats.TrackStatsCalculator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** One run of speeding up or slowing down on a stored track (docs/06_system_analysis.md §3.11, item 5). */
data class AccelerationEpisode(
    /** [AccelerationState.ACCELERATING] or [AccelerationState.DECELERATING]. */
    val kind: AccelerationState,
    /** Vertex where it starts — the standing point when it started [fromRest]; the scrubber goes here. */
    val startIndex: Int,
    /** Vertex of the strongest acceleration ([peakMps2]). */
    val peakIndex: Int,
    /** Vertex where it ends — the standing point when it ended [toRest] at one. */
    val endIndex: Int,
    val fromSpeedMps: Float,
    val toSpeedMps: Float,
    /** Started from a standstill: the speed before the first moving point is completed down to 0. */
    val fromRest: Boolean,
    /** Ended in a standstill: the speed after the last moving point is completed down to 0. */
    val toRest: Boolean,
    val durationMs: Long,
    /** Strongest acceleration of the run, signed (< 0 slowing down). */
    val peakMps2: Float,
    /** Speed change over the duration, signed. */
    val meanMps2: Float,
    /** Distance from the track start at [startIndex]. */
    val startDistanceM: Double,
) {
    val speedingUp: Boolean get() = kind == AccelerationState.ACCELERATING
}

/**
 * Along-track acceleration of a stored track, for its detail screen (US-24, ADR-30, docs/06_system_analysis.md §3.11):
 * per vertex of its [TrackLine] the estimate and the direction, the runs of speeding up and slowing down ([episodes])
 * and the scale of the line's colours. Computed from the stored points when the track is opened — for tracks recorded
 * before 1.2.0 too — and never stored.
 *
 * The formula is the live indicator's ([AccelerationMath], §3.10), fed only with Doppler speeds (a speed derived from
 * the displacement would turn metres of position noise into m/s²), but over a window centred on each point: the colour
 * and the episodes sit where the speed changed, without the live value's half-window delay (OBS-13).
 */
class TrackAcceleration private constructor(
    private val values: FloatArray,
    private val states: ByteArray,
    /** In the order they happened. */
    val episodes: List<AccelerationEpisode>,
    /** Half-range of the colour scale, m/s²: the line is fully coloured from ±this on (item 7). */
    val scaleMps2: Float,
    private val movingEstimates: Int,
) {
    /** Vertices, as in the [TrackLine] it was computed for. */
    val size: Int get() = values.size

    /** Enough estimates while moving for the line to be worth colouring by acceleration (item 8). */
    val isAvailable: Boolean get() = movingEstimates >= MIN_MOVING_ESTIMATES

    /** Episodes to show (tiles, "Speeding up and slowing down"): only on a track whose acceleration is shown at all. */
    val hasEpisodes: Boolean get() = isAvailable && episodes.isNotEmpty()

    /** Acceleration at vertex [index], m/s²; NaN where there is no estimate. */
    fun mps2(index: Int): Float = if (index in values.indices) values[index] else Float.NaN

    /** Direction at vertex [index]; null where there is no estimate. */
    fun state(index: Int): AccelerationState? {
        val s = states.getOrNull(index) ?: return null
        return if (s == NO_STATE) null else STATES[s.toInt()]
    }

    /** The strongest speeding up, by its peak — the "Max speed-up" tile and the first row of its kind. */
    val maxAcceleration: AccelerationEpisode? = episodes.filter { it.speedingUp }.maxByOrNull { it.peakMps2 }

    /** The strongest slowing down, by its peak. */
    val maxDeceleration: AccelerationEpisode? = episodes.filter { !it.speedingUp }.minByOrNull { it.peakMps2 }

    /** Up to [perKind] strongest episodes of each kind, the strongest first. */
    fun strongest(perKind: Int = STRONGEST_PER_KIND): List<AccelerationEpisode> {
        val up = episodes.filter { it.speedingUp }.sortedByDescending { it.peakMps2 }.take(perKind)
        val down = episodes.filter { !it.speedingUp }.sortedBy { it.peakMps2 }.take(perKind)
        return (up + down).sortedByDescending { abs(it.peakMps2) }
    }

    /** Vertices where an episode starts or ends, ascending: a simplified line keeps them so no episode is averaged away. */
    val boundaries: IntArray = episodes.flatMap { listOf(it.startIndex, it.endIndex) }.distinct().sorted().toIntArray()

    /** [cursor] with the acceleration at its vertex. */
    fun annotate(cursor: TrackCursor): TrackCursor {
        val a = mps2(cursor.index)
        return if (a.isNaN()) cursor else cursor.copy(accelerationMps2 = a, accelerationState = state(cursor.index))
    }

    companion object {
        val NONE = TrackAcceleration(FloatArray(0), ByteArray(0), emptyList(), MIN_SCALE_MPS2, 0)

        /** Half of the live window plus a margin for fix-time jitter and a walk thinned to a point every 2 s (item 3). */
        const val HALF_WINDOW_MS = AccelerationEstimator.WINDOW_MS / 2 + 250

        /** A stored speed equal to the displacement speed within this share was derived from the position (item 1). */
        const val DERIVED_TOLERANCE = 1e-6f

        const val MIN_MOVING_ESTIMATES = 10
        const val MIN_EPISODE_MS = 2_000L
        const val MIN_EPISODE_PEAK_MPS2 = 0.5f

        /** Speed change an episode needs: 10 % of the track's max speed, within these bounds (item 5). */
        const val MIN_EPISODE_DV_MPS = 1.5f
        const val MAX_EPISODE_DV_MPS = 3.0f
        const val EPISODE_DV_SHARE = 0.1f

        /** Trimming an episode to where the speed really changes: within this of its extreme speed (item 5). */
        const val TRIM_MIN_MPS = 0.3f
        const val TRIM_SHARE = 0.05f

        /**
         * Completion from / to a standstill: at most this speed, reached within this time at the edge's rate. A fix is
         * stored after max(2 m, a quarter of its accuracy), and a fused provider smooths the speed, so the last stored
         * moving point before a stop was at 5.9 m/s on the emulator (TC-147).
         */
        const val REST_MAX_MPS = 8f
        const val REST_MAX_MS = 3_000L

        const val MIN_SCALE_MPS2 = 1f
        const val MAX_SCALE_MPS2 = 6f
        const val SCALE_PERCENTILE = 0.95
        private const val HISTOGRAM_STEP_MPS2 = 0.05f

        const val STRONGEST_PER_KIND = 3

        private const val NO_STATE: Byte = -1
        private val STATES = AccelerationState.entries.toTypedArray()

        /** Acceleration along [line], built from [points] (the same points, in the same order). */
        fun of(points: List<TrackPoint>, line: TrackLine): TrackAcceleration {
            if (points.size < AccelerationEstimator.MIN_SAMPLES || line.size != points.size) return NONE
            return Computation(points, line).run()
        }
    }

    private class Computation(private val points: List<TrackPoint>, private val line: TrackLine) {
        private val n = points.size
        private val values = FloatArray(n) { Float.NaN }
        private val states = ByteArray(n) { NO_STATE }
        private val episodes = ArrayList<AccelerationEpisode>()

        // One series of usable speeds (item 2): point index, time, speed, weight.
        private val sIndex = IntArray(n)
        private val sTime = LongArray(n)
        private val sSpeed = FloatArray(n)
        private val sWeight = FloatArray(n)
        private var sCount = 0

        private val minEpisodeDv: Float

        init {
            var vMax = 0f
            for (i in 0 until n) vMax = max(vMax, line.speedMps(i))
            minEpisodeDv = (EPISODE_DV_SHARE * vMax).coerceIn(MIN_EPISODE_DV_MPS, MAX_EPISODE_DV_MPS)
        }

        fun run(): TrackAcceleration {
            for (s in 0 until line.segmentCount) segment(line.segmentStart(s), line.segmentEnd(s))
            var moving = 0
            val histogram = IntArray((AccelerationEstimator.MAX_PLAUSIBLE_MPS2 / HISTOGRAM_STEP_MPS2).toInt() + 1)
            for (k in 0 until n) {
                val a = values[k]
                if (a.isNaN() || states[k] == AccelerationState.STATIONARY.ordinal.toByte()) continue
                moving++
                histogram[min((abs(a) / HISTOGRAM_STEP_MPS2).toInt(), histogram.size - 1)]++
            }
            return TrackAcceleration(values, states, episodes, scale(histogram, moving), moving)
        }

        /** Item 7: the 95th percentile of |a| while moving, rounded up to 0.5, within 1…6 m/s². */
        private fun scale(histogram: IntArray, moving: Int): Float {
            if (moving == 0) return MIN_SCALE_MPS2
            val target = ceil(SCALE_PERCENTILE * moving).toInt()
            var cumulative = 0
            var bin = 0
            while (bin < histogram.size) {
                cumulative += histogram[bin]
                if (cumulative >= target) break
                bin++
            }
            val p95 = (bin + 1) * HISTOGRAM_STEP_MPS2
            return (ceil(p95 * 2f) / 2f).coerceIn(MIN_SCALE_MPS2, MAX_SCALE_MPS2)
        }

        /** Items 1–2 over the vertices [start] until [end] of one segment; each finished series goes to [series]. */
        private fun segment(start: Int, end: Int) {
            sCount = 0
            var distrustedUntil = Long.MIN_VALUE
            var outliersInRow = 0
            for (i in start until end) {
                val p = points[i]
                val v = p.speedMps ?: continue
                val sigma = p.speedAccuracyMps
                val doppler = when {
                    // Since 1.2.0 the accuracy is stored only with the receiver's own speed.
                    sigma != null -> true
                    // A segment's first point holds the receiver's speed, or 0 when it reported none.
                    i == start -> v > 0f
                    else -> {
                        val derived = isDisplacementSpeed(points[i - 1], p, v)
                        // The receiver claimed a standstill while the position moved: its speed is not trusted for a while.
                        if (derived && v > LiveMotion.CONTRADICTION_MPS) distrustedUntil = p.timestamp + LiveMotion.DISTRUST_MS
                        !derived
                    }
                }
                if (!doppler || p.timestamp <= distrustedUntil) continue
                if (sigma != null && sigma > LiveMotion.MAX_SPEED_ACCURACY_MPS) continue
                if (sCount > 0) {
                    val dtMs = p.timestamp - sTime[sCount - 1]
                    if (dtMs <= 0) continue
                    if (dtMs > AccelerationEstimator.MAX_GAP_MS) {
                        series(start, end)
                    } else if (AccelerationMath.isOutlier(v - sSpeed[sCount - 1], dtMs)) {
                        // One impossible step is the receiver's glitch; a second one in a row means the speed is elsewhere.
                        if (++outliersInRow < 2) continue
                        series(start, end)
                    }
                }
                outliersInRow = 0
                sIndex[sCount] = i
                sTime[sCount] = p.timestamp
                sSpeed[sCount] = v
                sWeight[sCount] = AccelerationMath.weight(sigma ?: LiveMotion.DEFAULT_SPEED_ACCURACY_MPS)
                sCount++
            }
            series(start, end)
        }

        /** Speed `effectiveSpeed` took from the displacement: exactly what `LocationFilter` computed, the same way. */
        private fun isDisplacementSpeed(previous: TrackPoint, p: TrackPoint, v: Float): Boolean {
            val dtMs = p.timestamp - previous.timestamp
            if (dtMs <= 0) return false
            val implied = (Geo.distanceMeters(previous.lat, previous.lon, p.lat, p.lon) / (dtMs / 1000.0)).toFloat()
            return abs(v - implied) <= DERIVED_TOLERANCE * max(1f, implied)
        }

        /** Items 3–5 for the current series of the segment [segStart] until [segEnd]; then starts the next series. */
        private fun series(segStart: Int, segEnd: Int) {
            val m = sCount
            sCount = 0
            if (m < AccelerationEstimator.MIN_SAMPLES) return
            val first = sIndex[0]
            val last = sIndex[m - 1]
            var lo = 0
            var hi = 0
            var state = AccelerationState.STEADY
            for (k in first..last) {
                val tk = points[k].timestamp
                while (lo < m && tk - sTime[lo] > HALF_WINDOW_MS) lo++
                while (hi + 1 < m && sTime[hi + 1] - tk <= HALF_WINDOW_MS) hi++
                val count = hi - lo + 1
                if (count < AccelerationEstimator.MIN_SAMPLES) continue
                if (sTime[hi] - sTime[lo] < AccelerationEstimator.MIN_SPAN_MS) continue
                // Times relative to the newest speed of the window, as the live estimate does.
                val fit = AccelerationMath.fit(count, sTime[hi], { sTime[lo + it] }, { sSpeed[lo + it] }, { sWeight[lo + it] })
                    ?: continue
                state = if (fit.stationary) AccelerationState.STATIONARY else AccelerationEstimator.nextState(state, fit.mps2)
                values[k] = fit.mps2
                states[k] = state.ordinal.toByte()
            }
            var k = first
            while (k <= last) {
                val s = states[k]
                val kind = if (s == NO_STATE) null else STATES[s.toInt()]
                if (kind != AccelerationState.ACCELERATING && kind != AccelerationState.DECELERATING) {
                    k++
                    continue
                }
                var e = k
                while (e < last && states[e + 1] == s) e++
                episode(kind, k, e, first, last, segStart, segEnd)?.let(episodes::add)
                k = e + 1
            }
        }

        /** Item 5: the run [s]..[e] of one direction, trimmed, completed to a standstill where it starts or ends at one. */
        private fun episode(
            kind: AccelerationState,
            s: Int,
            e: Int,
            first: Int,
            last: Int,
            segStart: Int,
            segEnd: Int,
        ): AccelerationEpisode? {
            val up = kind == AccelerationState.ACCELERATING
            var peak = s
            for (j in s..e) if (if (up) values[j] > values[peak] else values[j] < values[peak]) peak = j
            if (abs(values[peak]) < MIN_EPISODE_PEAK_MPS2) return null

            // Standing still stores a point every 30 s only — a shorter stop (a traffic light) none at all — so a series
            // starts already moving and ends still moving; its thinned edge points may even be too far apart for a
            // window of their own. A start from rest: the run begins at the series' first estimate, and the track stood
            // just before the series' first moving point (a standing point at the series' start, or next to it).
            var moving = first // first moving point of the series
            var restBefore = false
            if (up && noEstimate(first, s - 1)) {
                while (moving < s && points[moving].isStanding()) moving++
                restBefore = moving > first || (first > segStart && stood(first - 1, first, first - 1))
            }
            var stopping = last // last moving point of the series
            var restAfter = false
            if (!up && noEstimate(e + 1, last)) {
                while (stopping > e && points[stopping].isStanding()) stopping--
                // The recording may stop before the 30 s standstill point is stored: no next point counts as standing.
                restAfter = stopping < last || last + 1 >= segEnd || stood(last, last + 1, last + 1)
            }
            val runStart = if (restBefore) moving else s
            val runEnd = if (restAfter) stopping else e

            // The centred window sees a ramp before it starts and after it ends: trim to the speed's extremes.
            var from = line.speedMps(runStart)
            for (j in runStart..peak) from = if (up) min(from, line.speedMps(j)) else max(from, line.speedMps(j))
            var to = line.speedMps(peak)
            for (j in peak..runEnd) to = if (up) max(to, line.speedMps(j)) else min(to, line.speedMps(j))
            val delta = max(TRIM_MIN_MPS, TRIM_SHARE * abs(to - from))
            var startIndex = runStart
            for (j in runStart..peak) if (abs(line.speedMps(j) - from) <= delta) startIndex = j
            var endIndex = runEnd
            for (j in peak..runEnd) {
                if (abs(line.speedMps(j) - to) <= delta) {
                    endIndex = j
                    break
                }
            }
            var startMs = line.timestamp(startIndex).toDouble()
            var endMs = line.timestamp(endIndex).toDouble()

            // Complete the speed to 0 when the edge's own rate (its estimate, else the run's first / last one) gets
            // there within REST_MAX_MS, not earlier than the standing point and not later than the next stored point.
            var fromRest = false
            if (restBefore && from <= REST_MAX_MPS) {
                val restMs = restTimeMs(from, values[startIndex].takeUnless { it.isNaN() } ?: values[s])
                if (restMs != null) {
                    val rest = moving - 1
                    startMs = max(startMs - restMs, line.timestamp(rest).toDouble())
                    // The place it started from: the standing point, unless the run already begins standing still.
                    if (points[rest].isStanding() && !points[startIndex].isStanding()) startIndex = rest
                    from = 0f
                    fromRest = true
                }
            }
            var toRest = false
            if (restAfter && to <= REST_MAX_MPS) {
                val restMs = restTimeMs(to, -(values[endIndex].takeUnless { it.isNaN() } ?: values[e]))
                if (restMs != null) {
                    val rest = stopping + 1
                    endMs = if (rest < segEnd) min(endMs + restMs, line.timestamp(rest).toDouble()) else endMs + restMs
                    if (rest < segEnd && points[rest].isStanding() && !points[endIndex].isStanding()) endIndex = rest
                    to = 0f
                    toRest = true
                }
            }

            val durationMs = (endMs - startMs).toLong()
            val dv = to - from
            if (durationMs < MIN_EPISODE_MS || abs(dv) < minEpisodeDv) return null
            return AccelerationEpisode(
                kind = kind,
                startIndex = startIndex,
                peakIndex = peak,
                endIndex = endIndex,
                fromSpeedMps = from,
                toSpeedMps = to,
                fromRest = fromRest,
                toRest = toRest,
                durationMs = durationMs,
                peakMps2 = values[peak],
                meanMps2 = dv / (durationMs / 1000f),
                startDistanceM = line.distanceM(startIndex),
            )
        }

        /** No vertex in [from]..[to] has an estimate (true for an empty range). */
        private fun noEstimate(from: Int, to: Int): Boolean {
            for (k in from..to) if (!values[k].isNaN()) return false
            return true
        }

        /** Time to change by [speedMps] at [rateMps2] (> 0), when within [REST_MAX_MS]; null otherwise. */
        private fun restTimeMs(speedMps: Float, rateMps2: Float): Double? {
            if (rateMps2.isNaN() || rateMps2 <= 0f) return null
            val ms = speedMps / rateMps2 * 1000.0
            return ms.takeIf { it <= REST_MAX_MS }
        }

        private fun TrackPoint.isStanding(): Boolean = (speedMps ?: 0f) <= TrackStatsCalculator.MOVING_THRESHOLD_MPS

        /**
         * The track stood between the stored points [a] and [b] (a < b): [standing] — the one outside the series — stands,
         * or they are more than a series gap apart and the position barely moved in between (≤ 0.5 m/s on average): a
         * stop too short for a standing point of its own. A tunnel moves on, so it is no stop.
         */
        private fun stood(a: Int, b: Int, standing: Int): Boolean {
            if (points[standing].isStanding()) return true
            val dtMs = points[b].timestamp - points[a].timestamp
            if (dtMs <= AccelerationEstimator.MAX_GAP_MS) return false
            return !SpeedMath.isMoving(Geo.distanceMeters(points[a].lat, points[a].lon, points[b].lat, points[b].lon), dtMs)
        }
    }
}
