package com.justtracker.app.domain.maps

/** A downloaded, verified region: the map file (absolute path) and the bounds read from its header. */
data class RegionCoverage(val id: String, val file: String, val box: LatLonBox)

/** Where a tile (or viewport) should come from. */
sealed interface MapSource {
    /** Render from these Mapsforge files (all regions touching the area). */
    data class Offline(val files: List<String>) : MapSource

    /** Fetch from the online raster source (with the usual runtime cache). */
    data object Online : MapSource
}

/**
 * Decides per tile whether the offline renderer covers it (ADR-13). Pure so it can be unit-tested;
 * the map layer calls it for every tile request.
 */
object MapSourceResolver {
    /**
     * Below this zoom a region file is too coarse (and rendering the whole region per tile too slow);
     * overview tiles come from the online source / cache instead.
     */
    const val MIN_OFFLINE_ZOOM = 8

    fun resolve(regions: List<RegionCoverage>, area: LatLonBox): MapSource {
        val hits = regions.filter { it.box.intersects(area) }
        return if (hits.isEmpty()) MapSource.Online else MapSource.Offline(hits.map { it.file })
    }

    fun resolveForTile(regions: List<RegionCoverage>, zoom: Int, x: Int, y: Int): MapSource {
        if (zoom < MIN_OFFLINE_ZOOM || regions.isEmpty()) return MapSource.Online
        return resolve(regions, LatLonBox.ofTile(zoom, x, y))
    }
}
