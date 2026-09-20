package io.treklog.app.domain.stats

import io.treklog.app.domain.geo.Geo
import io.treklog.app.domain.model.TrackPoint

/**
 * Per-point view of a track used to colour the line by speed and to answer "how fast was I
 * here?" (docs/06_system_analysis.md §3.6). Arrays are parallel to the input point list.
 *
 * Speeds are the same 3-point-median smoothed values [TrackStatsCalculator] uses for max speed,
 * so the coloured line, the legend and the "Max speed" tile agree. Cumulative distance and
 * elapsed time are accumulated only inside a segment; a segment break (pause / recovery) neither
 * adds the gap distance nor connects the line.
 */
class SpeedProfile private constructor(
    val speedsMps: FloatArray,
    /** Distance from the start of the track up to each point, meters. */
    val distanceM: DoubleArray,
    /** Point timestamp, epoch millis (copied so callers need not keep the points). */
    val timestamps: LongArray,
    /** Segment number of each point, as recorded. */
    val segments: IntArray,
    /** GPS altitude of each point, meters; NaN when the fix had none. */
    val altitudesM: DoubleArray,
) {
    val size: Int get() = speedsMps.size

    /** Highest smoothed speed in the profile, 0 when empty. */
    val maxSpeedMps: Double get() = speedsMps.maxOrNull()?.toDouble() ?: 0.0

    companion object {
        val EMPTY = SpeedProfile(FloatArray(0), DoubleArray(0), LongArray(0), IntArray(0), DoubleArray(0))

        fun of(points: List<TrackPoint>): SpeedProfile {
            if (points.isEmpty()) return EMPTY
            val speeds = TrackStatsCalculator.smoothedSpeeds(points).toFloatArray()
            val distance = DoubleArray(points.size)
            val timestamps = LongArray(points.size)
            val segments = IntArray(points.size)
            val altitudes = DoubleArray(points.size)
            var acc = 0.0
            for (i in points.indices) {
                val p = points[i]
                if (i > 0) {
                    val q = points[i - 1]
                    if (q.segment == p.segment) acc += Geo.distanceMeters(q.lat, q.lon, p.lat, p.lon)
                }
                distance[i] = acc
                timestamps[i] = p.timestamp
                segments[i] = p.segment
                altitudes[i] = p.altitudeM ?: Double.NaN
            }
            return SpeedProfile(speeds, distance, timestamps, segments, altitudes)
        }
    }
}
