package com.justtracker.app.domain.poi

import java.util.Locale
import kotlin.math.floor

/**
 * Grid cell (~0.005° ≈ 550 m of latitude) every position is snapped to before any network request —
 * the user's live position and the vertices of a saved track alike ([TrackQuery]). Overpass queries are
 * built around *cell centers*, never exact fixes, so the server learns the area, not the precise location
 * (docs/07_security.md §2, ADR-09, ADR-21).
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

    /** Places shown along a saved track are at most this far from its real line (filtered on the device). */
    const val TRACK_RADIUS_M = 400

    /**
     * Radius around the cell-center polyline sent for a track: [TRACK_RADIUS_M] plus half a cell diagonal
     * (≤ 393 m, on the equator), so every place within [TRACK_RADIUS_M] of the trimmed real line is covered (ADR-21).
     */
    const val TRACK_QUERY_RADIUS_M = 800

    /** Overpass caps the polyline length; ~80 vertices keep the query well under limits. */
    const val MAX_POLYLINE_VERTICES = 80

    /** Overpass returns the first N by id, not the nearest, so we over-fetch and rank on the client. */
    const val MAX_RESULTS = 200

    /** Over-fetch for a track: its query corridor is twice as wide as the one shown. */
    const val MAX_TRACK_RESULTS = 400

    /** Pins shown around the user after ranking by distance to the cell center. */
    const val MAX_AROUND_CELL = 80

    /** Pins shown along a track after filtering and ranking by distance to its real line. */
    const val MAX_ALONG_TRACK = 60

    private const val TIMEOUT_S = 20

    /** Only objects that carry a Wikipedia article and a name; administrative boundaries are excluded. */
    private const val SELECTOR = "nwr[\"wikipedia\"][\"name\"][!\"boundary\"][!\"admin_level\"]"

    fun aroundCell(cell: GeoCell): String =
        "[out:json][timeout:$TIMEOUT_S];" +
            "$SELECTOR(around:$POINT_RADIUS_M,${fmt(cell.centerLat)},${fmt(cell.centerLon)});" +
            "out center $MAX_RESULTS;"

    /**
     * Objects near the cells a saved track passes through ([TrackQuery.cells]): at most [MAX_POLYLINE_VERTICES]
     * cell centers as a polyline, or a point query for a single cell. Takes cells, not coordinates, so a raw
     * fix cannot reach the server; the answer is filtered back to [TRACK_RADIUS_M] of the real line on the
     * device ([PoiProximity.alongLine]).
     */
    fun aroundTrackCells(cells: List<GeoCell>): String? {
        if (cells.isEmpty()) return null
        val coords = simplify(cells, MAX_POLYLINE_VERTICES).joinToString(",") { "${fmt(it.centerLat)},${fmt(it.centerLon)}" }
        return "[out:json][timeout:$TIMEOUT_S];" +
            "$SELECTOR(around:$TRACK_QUERY_RADIUS_M,$coords);" +
            "out center $MAX_TRACK_RESULTS;"
    }

    /** Keeps the first and last element and evenly spaced samples in between. */
    fun <T> simplify(points: List<T>, max: Int): List<T> {
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
