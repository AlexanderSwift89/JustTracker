package com.justtracker.app.domain.geo

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    /** Mean Earth radius (IUGG), meters. */
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance between two WGS84 points using the haversine formula. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Length of one degree of latitude (and of longitude on the equator), meters. */
    const val METERS_PER_DEGREE = EARTH_RADIUS_M * PI / 180.0

    /**
     * Shortest distance from P to the segment AB, meters. A local equirectangular projection around P is
     * exact enough for track segments (error well under 1 % below ~10 km); a zero-length segment is a point.
     */
    fun distanceToSegmentMeters(pLat: Double, pLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val kx = METERS_PER_DEGREE * cos(Math.toRadians(pLat))
        val ax = (aLon - pLon) * kx
        val ay = (aLat - pLat) * METERS_PER_DEGREE
        val dx = (bLon - aLon) * kx
        val dy = (bLat - aLat) * METERS_PER_DEGREE
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }
}
