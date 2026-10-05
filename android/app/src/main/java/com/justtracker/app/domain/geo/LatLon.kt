package com.justtracker.app.domain.geo

/** A WGS84 position in degrees. A value class of the domain: the map layer converts it to its own point type. */
data class LatLon(val lat: Double, val lon: Double)
