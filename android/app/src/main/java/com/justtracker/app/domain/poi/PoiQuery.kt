package com.justtracker.app.domain.poi

import java.util.Locale
import kotlin.math.floor

/**
 * Grid cell (~0.005° ≈ 550 m of latitude) the user's position is snapped to before any network
 * request. The Overpass query is built around the *cell center*, never the exact fix, so the
 * server learns the area, not the precise location (docs/07_security.md §2).
 */
data class GeoCell(val row: Int, val col: Int) {
    val centerLat: Double get() = (row + 0.5) * SIZE_DEG
    val centerLon: Double get() = (col + 0.5) * SIZE_DEG

    companion object {
        const val SIZE_DEG = 0.005

        fun of(lat: Double, lon: Double): GeoCell = GeoCell(floor(lat / SIZE_DEG).toInt(), floor(lon / SIZE_DEG).toInt())
    }
}

object OverpassQl {
    /** Radius around a cell center; must exceed half the cell diagonal plus the desired coverage. */
    const val POINT_RADIUS_M = 1500

    /** Radius around the track polyline in detail view. */
    const val TRACK_RADIUS_M = 400

    /** Overpass caps the polyline length; ~80 vertices keep the query well under limits. */
    const val MAX_POLYLINE_VERTICES = 80

    /** Overpass returns the first N by id, not the nearest, so we over-fetch and rank on the client. */
    const val MAX_RESULTS = 200

    /** Pins shown around the user after ranking by distance to the cell center. */
    const val MAX_AROUND_CELL = 80

    /** Pins shown along a track after ranking by distance to the (simplified) line. */
    const val MAX_ALONG_TRACK = 60

    private const val TIMEOUT_S = 20

    /** Only objects that carry a Wikipedia article and a name; administrative boundaries are excluded. */
    private const val SELECTOR = "nwr[\"wikipedia\"][\"name\"][!\"boundary\"][!\"admin_level\"]"

    fun aroundCell(cell: GeoCell): String =
        "[out:json][timeout:$TIMEOUT_S];" +
            "$SELECTOR(around:$POINT_RADIUS_M,${fmt(cell.centerLat)},${fmt(cell.centerLon)});" +
            "out center $MAX_RESULTS;"

    /**
     * Objects within [TRACK_RADIUS_M] of the polyline. Input is simplified to at most
     * [MAX_POLYLINE_VERTICES] evenly spaced vertices and rounded to ~11 m (4 decimals).
     */
    fun aroundPolyline(points: List<Pair<Double, Double>>): String? {
        val simplified = simplify(points, MAX_POLYLINE_VERTICES)
        if (simplified.size < 2) return null
        val coords = simplified.joinToString(",") { (lat, lon) -> "${fmt(lat)},${fmt(lon)}" }
        return "[out:json][timeout:$TIMEOUT_S];" +
            "$SELECTOR(around:$TRACK_RADIUS_M,$coords);" +
            "out center $MAX_RESULTS;"
    }

    /** Keeps the first and last point and evenly spaced samples in between. */
    fun simplify(points: List<Pair<Double, Double>>, max: Int): List<Pair<Double, Double>> {
        if (points.size <= max) return points
        val step = (points.size - 1).toDouble() / (max - 1)
        return List(max) { i -> points[Math.round(i * step).toInt().coerceAtMost(points.size - 1)] }
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.4f", v)
}

/** Builds [Poi] values from raw OSM tag maps (kept free of JSON so it is unit-testable). */
object PoiFactory {
    /**
     * @param preferredLang device language (`ru`); `name:ru` and `wikipedia:ru` win over the defaults.
     * @return null when the element has no usable name or Wikipedia reference.
     */
    fun fromTags(osmType: String, osmId: Long, lat: Double, lon: Double, tags: Map<String, String>, preferredLang: String): Poi? {
        val name = (tags["name:$preferredLang"] ?: tags["name"])?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val wiki = tags["wikipedia:$preferredLang"]?.let { WikipediaRef.parse("$preferredLang:$it") }
            ?: WikipediaRef.parse(tags["wikipedia"])
            ?: return null
        return Poi(
            id = "$osmType/$osmId",
            name = name.take(120),
            lat = lat,
            lon = lon,
            kind = kindOf(tags),
            wikipedia = wiki,
        )
    }

    fun kindOf(tags: Map<String, String>): PoiKind {
        val tourism = tags["tourism"]
        val amenity = tags["amenity"]
        val building = tags["building"]
        return when {
            tourism == "museum" || tourism == "gallery" -> PoiKind.MUSEUM
            amenity == "place_of_worship" || building in WORSHIP_BUILDINGS -> PoiKind.WORSHIP
            tags.containsKey("historic") -> PoiKind.HISTORIC
            tourism == "attraction" || tourism == "viewpoint" || tourism == "artwork" || tourism == "theme_park" ||
                amenity in CULTURE_AMENITIES -> PoiKind.ATTRACTION
            tags.containsKey("natural") || tags["leisure"] in NATURE_LEISURE || tags["landuse"] == "forest" -> PoiKind.NATURE
            else -> PoiKind.PLACE
        }
    }

    private val WORSHIP_BUILDINGS = setOf("church", "cathedral", "chapel", "mosque", "synagogue", "temple", "monastery")
    private val NATURE_LEISURE = setOf("park", "garden", "nature_reserve")
    private val CULTURE_AMENITIES = setOf("theatre", "arts_centre", "cinema", "planetarium")
}
