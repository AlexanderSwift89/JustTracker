package com.justtracker.app.domain.poi

import com.justtracker.app.domain.geo.Geo
import kotlin.math.abs
import kotlin.math.cos

/** Pure proximity rules for automatic announcements (docs/06_system_analysis.md §3.7). */
object PoiProximity {
    /** A place is announced once the user is within this distance. */
    const val ANNOUNCE_RADIUS_M = 150.0

    /** Returns the nearest not-yet-announced place within [radiusM], or null. */
    fun nextToAnnounce(
        pois: List<Poi>,
        lat: Double,
        lon: Double,
        announced: Set<String>,
        radiusM: Double = ANNOUNCE_RADIUS_M,
    ): Poi? = pois.asSequence()
        .filter { it.id !in announced }
        .map { it to Geo.distanceMeters(lat, lon, it.lat, it.lon) }
        .filter { (_, d) -> d <= radiusM }
        .minByOrNull { (_, d) -> d }
        ?.first

    fun distanceTo(poi: Poi, lat: Double, lon: Double): Double = Geo.distanceMeters(lat, lon, poi.lat, poi.lon)

    /** The [limit] places closest to the given point, nearest first. */
    fun nearest(pois: List<Poi>, lat: Double, lon: Double, limit: Int): List<Poi> =
        pois.sortedBy { distanceTo(it, lat, lon) }.take(limit)

    /** Shortest distance from the point to the polyline's segments; a one-vertex line is a point. */
    fun distanceToLine(lat: Double, lon: Double, line: List<Pair<Double, Double>>): Double {
        if (line.isEmpty()) return Double.POSITIVE_INFINITY
        if (line.size == 1) return Geo.distanceMeters(lat, lon, line[0].first, line[0].second)
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until line.size - 1) {
            val (aLat, aLon) = line[i]
            val (bLat, bLon) = line[i + 1]
            best = minOf(best, Geo.distanceToSegmentMeters(lat, lon, aLat, aLon, bLat, bLon))
        }
        return best
    }

    /**
     * Places within [radiusM] of a track's real line (e.g. [TrackQuery.filterLine]), nearest first, at most
     * [limit]. The Overpass answer covers a wider corridor around cell centers (ADR-21), so this filter is
     * what keeps the shown places "along the track". A bounding-box pre-check skips most segment scans.
     */
    fun alongLine(
        pois: List<Poi>,
        line: List<Pair<Double, Double>>,
        limit: Int,
        radiusM: Double = OverpassQl.TRACK_RADIUS_M.toDouble(),
    ): List<Poi> {
        if (line.isEmpty()) return emptyList()
        val minLat = line.minOf { it.first }
        val maxLat = line.maxOf { it.first }
        val minLon = line.minOf { it.second }
        val maxLon = line.maxOf { it.second }
        val dLat = radiusM / Geo.METERS_PER_DEGREE
        val maxAbsLat = maxOf(abs(minLat), abs(maxLat))
        val dLon = radiusM / (Geo.METERS_PER_DEGREE * cos(Math.toRadians(maxAbsLat)).coerceAtLeast(0.01))
        return pois.asSequence()
            .filter { it.lat in (minLat - dLat)..(maxLat + dLat) && it.lon in (minLon - dLon)..(maxLon + dLon) }
            .map { it to distanceToLine(it.lat, it.lon, line) }
            .filter { (_, d) -> d <= radiusM }
            .sortedBy { (_, d) -> d }
            .take(limit)
            .map { (poi, _) -> poi }
            .toList()
    }
}
