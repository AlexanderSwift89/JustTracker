package com.justtracker.app.domain.maps

import java.security.MessageDigest

/** Identity of one region file as far as rendered tiles are concerned: path, size and modification time. */
data class MapFileStamp(val path: String, val sizeBytes: Long, val modifiedAt: Long)

/**
 * Pure rules of the offline tile pipeline (docs/05_architecture.md §12, ADR-17): how big a rendered
 * tile is, how many threads render, and how the on-disk tile cache is keyed so that stale tiles of a
 * removed / re-downloaded region or another label language are never shown.
 */
object OfflineTiles {
    /** Base tile size of the Web Mercator grid; also what osmdroid assumes for the online source. */
    const val BASE_TILE_SIZE = 256

    /** Rendered tiles are drawn at `256·density` px on screen; above this density a 512 px tile keeps them crisp. */
    const val HI_DPI_THRESHOLD = 1.5f

    /** Highest zoom the map view allows; the module never renders beyond it. */
    const val MAX_ZOOM = 20

    const val MIN_RENDER_THREADS = 2
    const val MAX_RENDER_THREADS = 4

    /** Cached rendered tiles are re-rendered after this age (30 days); the size-based trim usually wins earlier. */
    const val TILE_EXPIRY_MS = 30L * 24 * 60 * 60 * 1000

    /** Cache provider name prefix; the region-set fingerprint is appended so a changed set gets a fresh namespace. */
    const val SOURCE_PREFIX = "JustTrackerOffline"

    /**
     * 512 px tiles on dense screens (xhdpi and above), 256 px otherwise. A 512 px tile costs ~2× the
     * raster work of a 256 px one but is drawn at 1.4× instead of 2.75× upscaling on a typical phone.
     */
    fun tileSizeFor(density: Float): Int = if (density >= HI_DPI_THRESHOLD) BASE_TILE_SIZE * 2 else BASE_TILE_SIZE

    /** Mapsforge scale factor matching [tileSizeFor]: line widths, text and symbols grow with the tile. */
    fun scaleFor(tileSize: Int): Float = tileSize.toFloat() / BASE_TILE_SIZE

    /** Half of the cores, at least [MIN_RENDER_THREADS] and at most [MAX_RENDER_THREADS] — the UI thread keeps a core. */
    fun renderThreads(cores: Int): Int = (cores / 2).coerceIn(MIN_RENDER_THREADS, MAX_RENDER_THREADS)

    /**
     * Stable digest of everything a rendered tile depends on: the region files (path, size, mtime —
     * order-insensitive), the label language and the tile size. Same inputs → same cache namespace.
     */
    fun fingerprint(files: List<MapFileStamp>, language: String?, tileSize: Int): String {
        val text = buildString {
            files.sortedBy { it.path }.forEach { append(it.path).append('|').append(it.sizeBytes).append('|').append(it.modifiedAt).append('\n') }
            append("lang=").append(language.orEmpty()).append('\n')
            append("tile=").append(tileSize)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    /** osmdroid tile-source name under which tiles of this region set are cached. */
    fun sourceName(fingerprint: String): String = "$SOURCE_PREFIX-$fingerprint"

    /** True for any cache namespace produced by [sourceName], current or stale. */
    fun isOfflineSource(name: String): Boolean = name.startsWith("$SOURCE_PREFIX-")
}
