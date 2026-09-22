package com.justtracker.app.data.maps.render

import android.content.Context
import android.graphics.Bitmap
import com.justtracker.app.domain.maps.OfflineTiles
import com.justtracker.app.util.AppLog
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Tag
import org.mapsforge.core.model.Tile
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.datastore.MapReadResult
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.renderer.DirectRenderer
import org.mapsforge.map.layer.renderer.RendererJob
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.InternalRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import org.osmdroid.util.MapTileIndex
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import kotlin.math.max
import kotlin.math.pow

/**
 * Process-wide part of the Mapsforge pipeline (ADR-17): the parsed render theme and the display model
 * it was scaled for. Parsing the theme XML and rasterising its symbols takes a few hundred ms, so it is
 * done once per process on a background thread; every [OfflineRenderer] (one per map view / region
 * set) shares it. Requires `AndroidGraphicFactory.createInstance()` — done in the Application.
 */
class OfflineRenderTheme private constructor(val tileSize: Int, private val xmlTheme: XmlRenderTheme) {
    /** Line widths, text and symbols are scaled with the tile so the map looks the same at any tile size. */
    val scale: Float = OfflineTiles.scaleFor(tileSize)

    val displayModel: DisplayModel = DisplayModel().apply {
        setFixedTileSize(tileSize)
        // AndroidGraphicFactory.createInstance() sets the static device scale factor to the display
        // density, but osmdroid already draws every tile at 256·density px on screen. The theme must
        // therefore be scaled by tileSize/256 only — with the density applied twice, labels and symbols
        // came out density× too big (OBS-05).
        setUserScaleFactor(scale / DisplayModel.getDeviceScaleFactor())
    }

    val future: RenderThemeFuture = RenderThemeFuture(AndroidGraphicFactory.INSTANCE, xmlTheme, displayModel)

    init {
        // Parse off the main thread; RenderContext.get() blocks render threads until it is done.
        Thread({
            future.run()
            prewarm()
        }, "offline-theme").start()
    }

    /**
     * Mapsforge scales stroke widths and font sizes per zoom level lazily inside `synchronized` theme
     * methods while render threads read the resulting per-zoom maps without a lock. Computing every
     * zoom level once up front, before any tile is rendered, keeps the render threads read-only.
     */
    private fun prewarm() {
        val theme = runCatching { future.get() }.getOrElse {
            AppLog.e("Offline render theme failed to load", it)
            return
        }
        for (zoom in 0..OfflineTiles.MAX_ZOOM + 2) {
            theme.scaleStrokeWidth(strokeScaleFor(zoom), zoom.toByte())
            theme.scaleTextSize(TEXT_SCALE, zoom.toByte())
        }
    }

    companion object {
        /** Same formula as mapsforge's `RenderContext.setScaleStrokeWidth`, so the prewarmed values are reused as-is. */
        fun strokeScaleFor(zoom: Int): Float = 1.5.pow(max(zoom - 12, 0)).toFloat()

        /** Text is already scaled through the display model; the per-job factor stays neutral. */
        const val TEXT_SCALE = 1f

        fun create(context: Context, theme: XmlRenderTheme = InternalRenderTheme.OSMARENDER): OfflineRenderTheme {
            val density = context.resources.displayMetrics.density
            return OfflineRenderTheme(OfflineTiles.tileSizeFor(density), theme)
        }
    }
}

/**
 * Renders osmdroid tiles from a fixed set of Mapsforge region files, from several threads at once
 * (ADR-17). `osmdroid-mapsforge`'s `MapsForgeTileSource.renderTile` is `synchronized` — one core
 * rendered while the others queued — and the `MapFile` reader keeps an unsynchronised index cache, so
 * this class shares one [DirectRenderer] (label placement across tile borders stays consistent) but
 * gives every concurrent render its own [MultiMapDataStore] from a small pool.
 *
 * [close] is idempotent; renders in flight finish on their own store and return `null` afterwards.
 */
class OfflineRenderer(
    files: List<String>,
    private val language: String?,
    private val theme: OfflineRenderTheme,
    poolSize: Int,
) {
    private val files: List<File> = files.map(::File)
    private val current = ThreadLocal<MapDataStore>()
    private val idle = ConcurrentLinkedQueue<MultiMapDataStore>()
    private val permits = Semaphore(poolSize)

    @Volatile
    private var closed = false

    /** The renderer reads through this delegate, which points at whatever store the calling thread holds. */
    private val delegate = object : MapDataStore() {
        private val store: MapDataStore? get() = current.get()
        override fun boundingBox(): BoundingBox = store?.boundingBox() ?: EMPTY_BOX
        override fun close() = Unit
        override fun getDataTimestamp(tile: Tile): Long = store?.getDataTimestamp(tile) ?: 0L
        override fun readLabels(tile: Tile): MapReadResult = store?.readLabels(tile) ?: MapReadResult()
        override fun readLabels(upperLeft: Tile, lowerRight: Tile): MapReadResult = store?.readLabels(upperLeft, lowerRight) ?: MapReadResult()
        override fun readMapData(tile: Tile): MapReadResult = store?.readMapData(tile) ?: MapReadResult()
        override fun readMapData(upperLeft: Tile, lowerRight: Tile): MapReadResult = store?.readMapData(upperLeft, lowerRight) ?: MapReadResult()
        override fun readPoiData(tile: Tile): MapReadResult = store?.readPoiData(tile) ?: MapReadResult()
        override fun readPoiData(upperLeft: Tile, lowerRight: Tile): MapReadResult = store?.readPoiData(upperLeft, lowerRight) ?: MapReadResult()
        override fun startPosition(): LatLong? = store?.startPosition()
        override fun startZoomLevel(): Byte? = store?.startZoomLevel()
        override fun supportsTile(tile: Tile): Boolean = store?.supportsTile(tile) ?: false
        override fun wayAsLabelTagFilter(tags: List<Tag>?): Boolean = store?.wayAsLabelTagFilter(tags) ?: false
    }

    private val renderer = DirectRenderer(delegate, AndroidGraphicFactory.INSTANCE, true, null)

    /** Renders one tile; null when the renderer is closed, no file opened or Mapsforge failed on the tile. */
    fun render(index: Long): Bitmap? {
        if (closed) return null
        val tile = Tile(MapTileIndex.getX(index), MapTileIndex.getY(index), MapTileIndex.getZoom(index).toByte(), theme.tileSize)
        val store = acquire() ?: return null
        current.set(store)
        try {
            val job = RendererJob(tile, delegate, theme.future, theme.displayModel, OfflineRenderTheme.TEXT_SCALE, false, false)
            // executeJob catches its own exceptions and returns null for the tile.
            val bitmap = renderer.executeJob(job) ?: return null
            return AndroidGraphicFactory.getBitmap(bitmap)
        } finally {
            current.remove()
            release(store)
        }
    }

    private fun acquire(): MultiMapDataStore? {
        permits.acquireUninterruptibly()
        if (closed) {
            permits.release()
            return null
        }
        return idle.poll() ?: open()
    }

    private fun release(store: MultiMapDataStore) {
        if (closed) runCatching { store.close() } else idle.add(store)
        permits.release()
    }

    private fun open(): MultiMapDataStore {
        val store = MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL)
        for (file in files) {
            runCatching { store.addMapDataStore(MapFile(file, language), false, false) }
                .onFailure { AppLog.w("Cannot open region file ${file.name}", it) }
        }
        return store
    }

    /** Closes idle stores now and the busy ones as soon as their render returns. */
    fun close() {
        closed = true
        while (true) {
            val store = idle.poll() ?: break
            runCatching { store.close() }
        }
    }

    private companion object {
        val EMPTY_BOX = BoundingBox(0.0, 0.0, 0.0, 0.0)
    }
}
