package io.treklog.app.ui.common

import io.treklog.app.domain.model.TrackPoint
import io.treklog.app.domain.stats.SpeedProfile
import org.osmdroid.util.GeoPoint

/**
 * One recording segment ready for the map: vertices plus, for every vertex, the smoothed speed,
 * the distance from the track start and the timestamp. Arrays are parallel to [points].
 */
class PathSegment(
    val points: List<GeoPoint>,
    val speedsMps: FloatArray,
    val distanceM: DoubleArray,
    val timestamps: LongArray,
    /** NaN when the fix had no altitude. */
    val altitudesM: DoubleArray,
) {
    val size: Int get() = points.size
}

/**
 * Position of the detail-screen scrubber on the track: a vertex addressed by its index over all
 * segments, plus everything the cursor panel shows (docs/04_ux_design.md §2.9).
 */
data class TrackCursor(
    /** Index over all segments' vertices, 0 until total point count. */
    val globalIndex: Int,
    val point: GeoPoint,
    val speedMps: Float,
    val distanceFromStartM: Double,
    /** 0..1 share of the total distance — the slider position. */
    val fraction: Float,
    val elapsedMs: Long,
    val timestamp: Long,
    /** null when the fix had no altitude. */
    val altitudeM: Double?,
)

/** Vertex the user tapped on the track line, resolved to the values the map cannot know. */
data class TrackTapInfo(
    val point: GeoPoint,
    val speedMps: Float,
    val distanceFromStartM: Double,
    /** Time since the track started at that point, ms. */
    val elapsedMs: Long,
)

object TrackPath {
    /** Groups points by segment (in order) and attaches the [SpeedProfile] values. */
    fun build(points: List<TrackPoint>): List<PathSegment> {
        if (points.isEmpty()) return emptyList()
        val profile = SpeedProfile.of(points)
        val result = ArrayList<PathSegment>()
        var start = 0
        for (i in 1..points.size) {
            if (i == points.size || profile.segments[i] != profile.segments[start]) {
                result.add(
                    PathSegment(
                        points = points.subList(start, i).map { GeoPoint(it.lat, it.lon) },
                        speedsMps = profile.speedsMps.copyOfRange(start, i),
                        distanceM = profile.distanceM.copyOfRange(start, i),
                        timestamps = profile.timestamps.copyOfRange(start, i),
                        altitudesM = profile.altitudesM.copyOfRange(start, i),
                    ),
                )
                start = i
            }
        }
        return result
    }

    fun pointCount(segments: List<PathSegment>): Int = segments.sumOf { it.size }

    /** Total track distance: the last vertex's cumulative distance (segments do not connect). */
    fun totalDistanceM(segments: List<PathSegment>): Double = segments.lastOrNull()?.distanceM?.lastOrNull() ?: 0.0

    fun globalIndex(segments: List<PathSegment>, segment: Int, index: Int): Int =
        segments.take(segment).sumOf { it.size } + index

    /** Vertex whose cumulative distance is nearest to [fraction] × total distance (slider → vertex). */
    fun indexForFraction(segments: List<PathSegment>, fraction: Float): Int {
        val target = totalDistanceM(segments) * fraction.coerceIn(0f, 1f)
        var offset = 0
        for (seg in segments) {
            if (seg.size > 0 && target <= seg.distanceM.last()) {
                var lo = 0
                var hi = seg.size - 1
                while (lo < hi) {
                    val mid = (lo + hi) / 2
                    if (seg.distanceM[mid] < target) lo = mid + 1 else hi = mid
                }
                // lo is the first vertex at/after target; pick the nearer of it and its predecessor.
                if (lo > 0 && target - seg.distanceM[lo - 1] < seg.distanceM[lo] - target) lo--
                return offset + lo
            }
            offset += seg.size
        }
        return (offset - 1).coerceAtLeast(0)
    }

    /** Resolves a global vertex index to a [TrackCursor]; null when out of range. */
    fun cursorAt(segments: List<PathSegment>, globalIndex: Int, startedAt: Long): TrackCursor? {
        if (globalIndex < 0) return null
        var offset = 0
        for (seg in segments) {
            if (globalIndex < offset + seg.size) {
                val i = globalIndex - offset
                val total = totalDistanceM(segments)
                val alt = seg.altitudesM[i]
                return TrackCursor(
                    globalIndex = globalIndex,
                    point = seg.points[i],
                    speedMps = seg.speedsMps[i],
                    distanceFromStartM = seg.distanceM[i],
                    fraction = if (total > 0) (seg.distanceM[i] / total).toFloat().coerceIn(0f, 1f) else 0f,
                    elapsedMs = (seg.timestamps[i] - startedAt).coerceAtLeast(0),
                    timestamp = seg.timestamps[i],
                    altitudeM = if (alt.isNaN()) null else alt,
                )
            }
            offset += seg.size
        }
        return null
    }

    /** Resolves a tapped vertex to its speed/distance/time. */
    fun tapInfo(segments: List<PathSegment>, segment: Int, index: Int, startedAt: Long): TrackTapInfo? {
        val seg = segments.getOrNull(segment) ?: return null
        if (index !in seg.points.indices) return null
        return TrackTapInfo(
            point = seg.points[index],
            speedMps = seg.speedsMps[index],
            distanceFromStartM = seg.distanceM[index],
            elapsedMs = (seg.timestamps[index] - startedAt).coerceAtLeast(0),
        )
    }
}
