package com.justtracker.app.data.maps.render

import android.content.Context
import android.graphics.drawable.Drawable
import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.domain.maps.MapSource
import com.justtracker.app.domain.maps.MapSourceResolver
import com.justtracker.app.domain.maps.RegionCoverage
import com.justtracker.app.util.AppLog
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.osmdroid.config.Configuration
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import java.io.File

/**
 * Tile provider for both map modes (ADR-13, ADR-16). It *is* osmdroid's default
 * [MapTileProviderBasic] (on-disk cache → archives → approximation → OSM download, with the
 * default cache-protection and pre-cache set-up — a bare `MapTileProviderArray` thrashes its LRU and
 * re-downloads the same tiles forever), extended per mode:
 *
 * - [MapMode.ONLINE]: the default chain as is; downloaded regions are not consulted.
 * - [MapMode.OFFLINE]: an [OfflineRegionModule] is put in front of the chain (tiles inside READY
 *   regions, zoom ≥ 8, rendered from the Mapsforge files) and the data connection is switched off,
 *   so uncovered tiles come only from the cache / approximation — never from the network.
 */
class HybridTileProvider private constructor(context: Context) : MapTileProviderBasic(context, ONLINE_SOURCE) {

    companion object {
        /** Online raster source; also the name under which cached tiles are stored. */
        val ONLINE_SOURCE: ITileSource = TileSourceFactory.MAPNIK

        /** Cache name for rendered offline tiles — kept distinct from MAPNIK on purpose. */
        const val OFFLINE_SOURCE_NAME = "JustTrackerOffline"

        fun create(
            context: Context,
            mode: MapMode,
            offline: MapsForgeTileSource?,
            coverage: () -> List<RegionCoverage>,
        ): HybridTileProvider {
            val app = context.applicationContext
            val provider = HybridTileProvider(app)
            if (mode == MapMode.OFFLINE) {
                if (offline != null) provider.mTileProviderList.add(0, OfflineRegionModule(offline, coverage))
                provider.setUseDataConnection(false)
            }
            return provider
        }

        /** One Mapsforge source over all READY region files; null when there is nothing to render. */
        fun createOfflineSource(files: List<String>, language: String?, theme: XmlRenderTheme? = null): MapsForgeTileSource? {
            if (files.isEmpty()) return null
            return runCatching {
                MapsForgeTileSource.createFromFiles(files.map(::File).toTypedArray(), theme, OFFLINE_SOURCE_NAME, language)
            }.onFailure { AppLog.e("Cannot open offline regions", it) }.getOrNull()
        }
    }
}

/**
 * Renders a tile from the region files only when [MapSourceResolver] says the tile is covered;
 * otherwise fails fast so the array asks the next module. Nothing is written to disk — Mapsforge
 * rendering is fast enough and osmdroid keeps an in-memory LRU of recent tiles.
 */
internal class OfflineRegionModule(
    private val source: MapsForgeTileSource,
    private val coverage: () -> List<RegionCoverage>,
) : MapTileModuleProviderBase(
    Configuration.getInstance().tileFileSystemThreads.toInt(),
    Configuration.getInstance().tileFileSystemMaxQueueSize.toInt(),
) {
    override fun getName(): String = "Offline regions"
    override fun getThreadGroupName(): String = "offlineregions"
    override fun getUsesDataConnection(): Boolean = false
    override fun getMinimumZoomLevel(): Int = MapSourceResolver.MIN_OFFLINE_ZOOM
    override fun getMaximumZoomLevel(): Int = source.maximumZoomLevel
    override fun setTileSource(tileSource: ITileSource) = Unit // fixed to the Mapsforge source

    override fun getTileLoader(): TileLoader = object : TileLoader() {
        override fun loadTile(pMapTileIndex: Long): Drawable? {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            if (MapSourceResolver.resolveForTile(coverage(), zoom, x, y) !is MapSource.Offline) return null
            return source.renderTile(pMapTileIndex)
        }
    }

    override fun detach() {
        super.detach()
        runCatching { source.dispose() }
    }
}
