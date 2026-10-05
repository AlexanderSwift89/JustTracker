package com.justtracker.app.domain.maps

/**
 * Sanity check of a downloaded region before it becomes READY (docs/07_security.md SEC-13): the bounds in the
 * `.map` header must be a real WGS84 box that overlaps the catalogue box and has a comparable area. Catalogue
 * boxes are approximate, so the check is loose; it rejects a swapped or corrupted file whose header claims, say,
 * the whole world — its coverage would turn offline tiles blank everywhere.
 */
object RegionPlausibility {
    /**
     * Allowed header/catalogue area ratio, either way. The real headers of the bundled catalogue deviate by at
     * most 8.9× (Malta: the file covers the surrounding sea; checked 2026-10-05), this keeps twice that headroom.
     */
    const val MAX_AREA_RATIO = 20.0

    fun headerMatchesCatalog(catalog: LatLonBox, header: LatLonBox): Boolean {
        val bounds = listOf(header.minLat, header.minLon, header.maxLat, header.maxLon)
        if (bounds.any { !it.isFinite() }) return false
        if (header.minLat < -90.0 || header.maxLat > 90.0 || header.minLon < -180.0 || header.maxLon > 180.0) return false
        if (!catalog.intersects(header)) return false
        val ratio = area(header) / area(catalog)
        return ratio in (1 / MAX_AREA_RATIO)..MAX_AREA_RATIO
    }

    /** Square degrees; a degenerate box counts as a tiny one instead of dividing by zero. */
    private fun area(box: LatLonBox): Double = maxOf((box.maxLat - box.minLat) * (box.maxLon - box.minLon), MIN_AREA_DEG2)

    private const val MIN_AREA_DEG2 = 1e-6
}
