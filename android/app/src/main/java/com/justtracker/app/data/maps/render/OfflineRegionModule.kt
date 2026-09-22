package com.justtracker.app.data.maps.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.justtracker.app.domain.maps.MapSource
import com.justtracker.app.domain.maps.MapSourceResolver
import com.justtracker.app.domain.maps.OfflineTiles
import com.justtracker.app.domain.maps.RegionCoverage
import com.justtracker.app.util.AppLog
import org.osmdroid.tileprovider.modules.DatabaseFileArchive
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.BitmapTileSourceBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.MapTileIndex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * osmdroid tile source describing rendered offline tiles: the cache namespace ([name], fingerprint of
 * the region set), the tile size and the zoom range. It never produces tiles itself — that is the
 * module's job — but the on-disk cache reader decodes PNGs through [getDrawable].
 */
class OfflineTileSource(name: String, val tileSize: Int) :
    BitmapTileSourceBase(name, MapSourceResolver.MIN_OFFLINE_ZOOM, OfflineTiles.MAX_ZOOM, tileSize, ".png") {

    /**
     * Opaque RGB_565 (half the memory of the default ARGB_8888) and a plain drawable: osmdroid's
     * reusable-bitmap pool is sized for 256 px online tiles and would only hoard ours.
     */
    @Suppress("UseKtx") // no Resources on purpose: density-neutral, as osmdroid wraps its own tiles
    override fun getDrawable(aFileInputStream: InputStream): Drawable? {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bitmap = BitmapFactory.decodeStream(aFileInputStream, null, options) ?: return null
        return BitmapDrawable(null, bitmap)
    }
}

/**
 * The shared osmdroid SQLite tile cache with one extra operation: dropping tiles rendered for a
 * region set / language / tile size that is no longer current (their namespace differs from [keep]).
 */
class OfflineTileWriter : SqlTileWriter() {
    fun purgeStale(keep: String): Int = runCatching {
        val database = db ?: return 0
        database.delete(
            DatabaseFileArchive.TABLE,
            "${DatabaseFileArchive.COLUMN_PROVIDER} LIKE ? AND ${DatabaseFileArchive.COLUMN_PROVIDER} <> ?",
            arrayOf("${OfflineTiles.SOURCE_PREFIX}-%", keep),
        )
    }.onFailure { AppLog.w("Stale offline tiles not purged", it) }.getOrDefault(0)
}

/**
 * Renders tiles inside READY regions on [threads] worker threads (ADR-17); tiles outside the coverage
 * fail fast so the provider array asks the next module. Every rendered tile is also handed to a
 * single low-priority writer thread that encodes it as PNG into the osmdroid SQLite cache under
 * [source]'s name, so the next visit of the area (and the approximation of the next zoom level) is
 * served from disk instead of a fresh render.
 */
internal class OfflineRegionModule(
    private val renderer: OfflineRenderer,
    private val source: OfflineTileSource,
    private val coverage: () -> List<RegionCoverage>,
    private val writer: IFilesystemCache?,
    threads: Int,
) : MapTileModuleProviderBase(threads, QUEUE_SIZE) {

    private val saver = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(SAVE_QUEUE_SIZE), { r ->
        Thread(r, "offline-tile-writer").apply { priority = Thread.MIN_PRIORITY }
    }, ThreadPoolExecutor.DiscardPolicy())

    override fun getName(): String = "Offline regions"
    override fun getThreadGroupName(): String = "offlineregions"
    override fun getUsesDataConnection(): Boolean = false
    override fun getMinimumZoomLevel(): Int = source.minimumZoomLevel
    override fun getMaximumZoomLevel(): Int = source.maximumZoomLevel
    override fun setTileSource(tileSource: ITileSource) = Unit // fixed to the offline source

    /** True when the tile lies inside a READY region (and within the offline zoom range). */
    fun covers(index: Long): Boolean {
        val zoom = MapTileIndex.getZoom(index)
        if (zoom < minimumZoomLevel || zoom > maximumZoomLevel) return false
        return MapSourceResolver.resolveForTile(coverage(), zoom, MapTileIndex.getX(index), MapTileIndex.getY(index)) is MapSource.Offline
    }

    override fun getTileLoader(): TileLoader = object : TileLoader() {
        @Suppress("UseKtx") // density-neutral drawable, see OfflineTileSource.getDrawable
        override fun loadTile(pMapTileIndex: Long): Drawable? {
            if (!covers(pMapTileIndex)) return null
            val bitmap = renderer.render(pMapTileIndex) ?: return null
            if (writer != null) saver.execute { save(pMapTileIndex, bitmap) }
            return BitmapDrawable(null, bitmap)
        }
    }

    private fun save(index: Long, bitmap: Bitmap) {
        val writer = writer ?: return
        runCatching {
            val bytes = ByteArrayOutputStream(64 * 1024).also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            writer.saveFile(source, index, ByteArrayInputStream(bytes), System.currentTimeMillis() + OfflineTiles.TILE_EXPIRY_MS)
        }.onFailure { AppLog.w("Offline tile not cached", it) }
    }

    override fun detach() {
        super.detach()
        saver.shutdown()
        renderer.close()
    }

    private companion object {
        /** Requests beyond this are dropped by osmdroid and re-issued on the next draw — keeps zoom gestures responsive. */
        const val QUEUE_SIZE = 40
        const val SAVE_QUEUE_SIZE = 64
    }
}
