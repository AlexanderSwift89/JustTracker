package com.justtracker.app.domain.poi

import com.justtracker.app.domain.geo.Geo

/**
 * What a saved track may reveal to Overpass (docs/05_architecture.md ADR-21): only the [GeoCell]s it passes
 * through, without its first and last [END_TRIM_M] — the ends of a track are usually home or work. The exact
 * line stays on the device and only filters and ranks the answer ([filterLine], [PoiProximity.alongLine]).
 */
object TrackQuery {
    /** Path length cut from each end; also the minimum straight-line distance kept from the first and last fix. */
    const val END_TRIM_M = 300.0

    /** Upper bound on the vertices of [filterLine] (plus its two ends). */
    const val FILTER_MAX_VERTICES = 2_000

    /** Minimum spacing of [filterLine] vertices: GPS jitter below this is irrelevant for a 400 m corridor. */
    private const val FILTER_MIN_STEP_M = 25.0

    /**
     * Cells of the track interior in path order, consecutive duplicates merged. When nothing is left after
     * trimming (a track under ~600 m, standing still) the cell of the point at half the length is used — the
     * same granularity the live lookup sends while recording. Empty only for an empty track.
     */
    fun cells(points: List<Pair<Double, Double>>): List<GeoCell> {
        if (points.isEmpty()) return emptyList()
        val kept = interior(points).ifEmpty { listOf(pointAtHalfLength(points)) }
        val out = ArrayList<GeoCell>()
        for ((lat, lon) in kept) {
            val cell = GeoCell.of(lat, lon)
            if (out.lastOrNull() != cell) out += cell
        }
        return out
    }

    /**
     * Points at least [END_TRIM_M] along the path from both ends *and* at least [END_TRIM_M] in a straight line
     * from the first and the last fix — a loop that passes the start again mid-track is cut there too.
     */
    internal fun interior(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (points.size < 2) return emptyList()
        val along = cumulativeLengths(points)
        val total = along.last()
        val (firstLat, firstLon) = points.first()
        val (lastLat, lastLon) = points.last()
        return points.filterIndexed { i, (lat, lon) ->
            along[i] >= END_TRIM_M && total - along[i] >= END_TRIM_M &&
                Geo.distanceMeters(firstLat, firstLon, lat, lon) >= END_TRIM_M &&
                Geo.distanceMeters(lastLat, lastLon, lat, lon) >= END_TRIM_M
        }
    }

    /** First point whose path distance reaches half the track length; the first point of a zero-length track. */
    internal fun pointAtHalfLength(points: List<Pair<Double, Double>>): Pair<Double, Double> {
        val along = cumulativeLengths(points)
        val half = along.last() / 2
        return points[along.indexOfFirst { it >= half }.coerceAtLeast(0)]
    }

    /**
     * The real line thinned by distance for on-device filtering: a vertex is kept once it is at least
     * max(25 m, length / [FILTER_MAX_VERTICES]) from the previous kept one, the last point always. Every original
     * point is within one step of the result, and the result has at most [FILTER_MAX_VERTICES] + 2 vertices.
     */
    fun filterLine(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (points.size <= 2) return points
        val step = maxOf(FILTER_MIN_STEP_M, cumulativeLengths(points).last() / FILTER_MAX_VERTICES)
        val out = ArrayList<Pair<Double, Double>>()
        var last = points.first()
        out += last
        for (i in 1 until points.size - 1) {
            val p = points[i]
            if (Geo.distanceMeters(last.first, last.second, p.first, p.second) >= step) {
                out += p
                last = p
            }
        }
        out += points.last()
        return out
    }

    private fun cumulativeLengths(points: List<Pair<Double, Double>>): DoubleArray {
        val along = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val (aLat, aLon) = points[i - 1]
            val (bLat, bLon) = points[i]
            along[i] = along[i - 1] + Geo.distanceMeters(aLat, aLon, bLat, bLon)
        }
        return along
    }
}
