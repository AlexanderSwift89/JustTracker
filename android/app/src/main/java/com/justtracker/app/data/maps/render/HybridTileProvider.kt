package com.justtracker.app.data.maps.render

import android.content.Context
import android.graphics.drawable.Drawable
import com.justtracker.app.domain.maps.MapSource
import com.justtracker.app.domain.maps.MapSourceResolver
import com.justtracker.app.domain.maps.RegionCoverage
import com.justtracker.app.util.AppLog
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.osmdroid.config.Configuration
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.IRegisterReceiver
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.MapTileApproximater
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.MapTileFilesystemProvider
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.modules.NetworkAvailabliltyCheck
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.MapTileIndex
import java.io.File

/**
 * Tile chain (ADR-13): offline Mapsforge renderer for tiles inside a downloaded region → on-disk
 * cache of online tiles → approximation from cached lower zooms → OSM download.
 *
 * Routing is per tile, so a viewport straddling a region border still shows everything, and the
 * online source is only asked for what the regions don't cover (OSM tile policy: on-demand only).
 */
class HybridTileProvider private constructor(
    receiver: IRegisterReceiver,
    modules: Array<MapTileModuleProviderBase>,
) : MapTileProviderArray(ONLINE_SOURCE, receiver, modules) {

    companion object {
        /** Online raster source; also the name under which cached tiles are stored. */
        val ONLINE_SOURCE: ITileSource = TileSourceFactory.MAPNIK

        /** Cache name for rendered offline tiles — kept distinct from MAPNIK on purpose. */
        const val OFFLINE_SOURCE_NAME = "JustTrackerOffline"

        fun create(context: Context, offline: MapsForgeTileSource?, coverage: () -> List<RegionCoverage>): HybridTileProvider {
            val receiver = SimpleRegisterReceiver(context.applicationContext)
            val cache = SqlTileWriter()
            val filesystem = MapTileFilesystemProvider(receiver, ONLINE_SOURCE)
            val approximater = MapTileApproximater().apply { addProvider(filesystem) }
            val downloader = MapTileDownloader(ONLINE_SOURCE, cache, NetworkAvailabliltyCheck(context.applicationContext))
            val modules = buildList {
                if (offline != null) add(OfflineRegionModule(offline, coverage))
                add(filesystem)
                add(approximater)
                add(downloader)
            }.toTypedArray()
            return HybridTileProvider(receiver, modules)
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
