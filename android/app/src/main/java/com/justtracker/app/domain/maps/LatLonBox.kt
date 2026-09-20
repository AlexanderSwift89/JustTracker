package com.justtracker.app.domain.maps

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sinh

/** Axis-aligned WGS84 box. Boxes crossing the antimeridian are not supported (split them in two). */
data class LatLonBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
) {
    init {
        require(minLat <= maxLat && minLon <= maxLon) { "Inverted box: $this" }
    }

    fun intersects(other: LatLonBox): Boolean =
        minLat <= other.maxLat && maxLat >= other.minLat &&
            minLon <= other.maxLon && maxLon >= other.minLon

    fun contains(lat: Double, lon: Double): Boolean =
        lat in minLat..maxLat && lon in minLon..maxLon

    companion object {
        /** Bounds of a slippy-map (XYZ, Web Mercator) tile. */
        fun ofTile(zoom: Int, x: Int, y: Int): LatLonBox {
            val n = 2.0.pow(zoom)
            val west = x / n * 360.0 - 180.0
            val east = (x + 1) / n * 360.0 - 180.0
            val north = tileLat(y.toDouble(), n)
            val south = tileLat((y + 1).toDouble(), n)
            return LatLonBox(minLat = south, minLon = west, maxLat = north, maxLon = east)
        }

        private fun tileLat(y: Double, n: Double): Double =
            Math.toDegrees(atan(sinh(PI * (1 - 2 * y / n))))
    }
}
