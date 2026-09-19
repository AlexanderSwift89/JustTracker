package io.treklog.app.domain.poi

import io.treklog.app.domain.geo.Geo

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

    /**
     * The [limit] places closest to a polyline, approximated by the distance to its nearest vertex
     * (vertices come from [OverpassQl.simplify], so ≤ 80 × ≤ 200 distance evaluations).
     */
    fun nearestToLine(pois: List<Poi>, line: List<Pair<Double, Double>>, limit: Int): List<Poi> {
        if (line.isEmpty() || pois.size <= limit) return pois
        return pois.sortedBy { poi -> line.minOf { (lat, lon) -> distanceTo(poi, lat, lon) } }.take(limit)
    }
}
