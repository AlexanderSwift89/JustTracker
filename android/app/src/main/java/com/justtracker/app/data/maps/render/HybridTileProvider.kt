package com.justtracker.app.data.maps.render

import android.content.Context
import com.justtracker.app.domain.maps.MapFileStamp
import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.domain.maps.OfflineTiles
import com.justtracker.app.domain.maps.RegionCoverage
import com.justtracker.app.util.AppLog
import org.osmdroid.tileprovider.IRegisterReceiver
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck
import org.osmdroid.tileprovider.modules.MapTileApproximater
import org.osmdroid.tileprovider.modules.MapTileAssetsProvider
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.modules.MapTileSqlCacheProvider
import org.osmdroid.tileprovider.modules.NetworkAvailabliltyCheck
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.MapTileAreaBorderComputer
import org.osmdroid.util.MapTileAreaZoomComputer
import org.osmdroid.util.MapTileIndex
import java.io.File
import java.util.concurrent.Executors

/**
 * Tile provider for both map modes (ADR-13, ADR-16, ADR-17). The online chain is the one osmdroid's
 * `MapTileProviderBasic` builds (assets → SQLite cache → archives → approximation from lower zooms →
 * OSM download; LRU-protection computers and pre-cache for the border ring and the next zoom-out
 * level — without them a bare array thrashed its cache, D-09). It is assembled here instead of
 * inherited so that in [MapMode.OFFLINE] the offline modules come *first* everywhere, including the
 * pre-cache, and cached online tiles never shadow a renderable region:
 *
 * ```
 * OFFLINE: [offline SQLite cache] → [approximation from cached offline tiles] → [OfflineRegionModule]
 *          → online chain without the downloader (setUseDataConnection(false))
 * ONLINE:  online chain as is; downloaded regions are not consulted
 * ```
 *
 * [isDowngradedMode] is the second half of the fix for "patchy" offline maps: osmdroid keeps an
 * expired or scaled placeholder for good whenever the data connection is off, so after a zoom the
 * blurry rescaled tiles were never replaced. A tile the renderer covers is never downgraded.
 */
class HybridTileProvider private constructor(
    context: Context,
    registerReceiver: IRegisterReceiver,
    private val networkCheck: INetworkAvailablityCheck,
    mode: MapMode,
    private val offline: OfflineRegionModule?,
    private val writer: OfflineTileWriter,
    offlineSource: OfflineTileSource?,
) : MapTileProviderArray(ONLINE_SOURCE, registerReceiver) {

    private val downloader: MapTileDownloader

    init {
        val preCache = tileCache.preCache
        if (offline != null && offlineSource != null) {
            val offlineCache = object : MapTileSqlCacheProvider(registerReceiver, offlineSource) {
                override fun setTileSource(tileSource: ITileSource) = Unit // never re-pointed at the online source
            }
            val offlineApproximation = MapTileApproximater().apply { addProvider(offlineCache) }
            mTileProviderList.add(offlineCache)
            mTileProviderList.add(offlineApproximation)
            mTileProviderList.add(offline)
            preCache.addProvider(offlineCache)
            preCache.addProvider(offline)
        }

        val assets = MapTileAssetsProvider(registerReceiver, context.assets, ONLINE_SOURCE)
        val fileSystem = MapTileSqlCacheProvider(registerReceiver, ONLINE_SOURCE)
        val archive = MapTileFileArchiveProvider(registerReceiver, ONLINE_SOURCE)
        val approximation = MapTileApproximater().apply {
            addProvider(assets)
            addProvider(fileSystem)
            addProvider(archive)
        }
        downloader = MapTileDownloader(ONLINE_SOURCE, writer, networkCheck)
        mTileProviderList.add(assets)
        mTileProviderList.add(fileSystem)
        mTileProviderList.add(archive)
        mTileProviderList.add(approximation)
        mTileProviderList.add(downloader)

        tileCache.protectedTileComputers.add(MapTileAreaZoomComputer(-1))
        tileCache.protectedTileComputers.add(MapTileAreaBorderComputer(1))
        tileCache.setAutoEnsureCapacity(false)
        tileCache.setStressedMemory(false)
        preCache.addProvider(assets)
        preCache.addProvider(fileSystem)
        preCache.addProvider(archive)
        preCache.addProvider(downloader)
        tileCache.protectedTileContainers.add(this)

        // OFFLINE never touches the network — even with no region to render (US-22).
        setUseDataConnection(mode == MapMode.ONLINE)
    }

    override fun getTileWriter(): IFilesystemCache = writer

    /**
     * Keep a stale / scaled tile instead of asking for a better one only when nothing better can come:
     * no network (or the data connection is off) and the renderer does not cover the tile, or the
     * zoom is outside the downloader's range.
     */
    override fun isDowngradedMode(pMapTileIndex: Long): Boolean {
        if (offline != null && offline.covers(pMapTileIndex)) return false
        if (!networkCheck.networkAvailable || !useDataConnection()) return true
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        return zoom < downloader.minimumZoomLevel || zoom > downloader.maximumZoomLevel
    }

    override fun detach() {
        writer.onDetach()
        super.detach()
    }

    companion object {
        /** Online raster source; also the name under which downloaded tiles are cached. */
        val ONLINE_SOURCE: ITileSource = TileSourceFactory.MAPNIK

        private val maintenance = Executors.newSingleThreadExecutor { r -> Thread(r, "offline-tile-maintenance") }

        /**
         * @param regionFiles absolute paths of the READY region files (ignored in [MapMode.ONLINE]).
         * @param language label language of the region files (ISO 639-1), as chosen in Settings.
         * @param theme the process-wide render theme; only resolved (and parsed) when a region is rendered.
         * @param coverage live view of the READY regions used for the per-tile coverage test.
         */
        fun create(
            context: Context,
            mode: MapMode,
            regionFiles: List<String>,
            language: String?,
            theme: () -> OfflineRenderTheme,
            coverage: () -> List<RegionCoverage>,
        ): HybridTileProvider {
            val app = context.applicationContext
            val writer = OfflineTileWriter()
            var module: OfflineRegionModule? = null
            var source: OfflineTileSource? = null
            if (mode == MapMode.OFFLINE && regionFiles.isNotEmpty()) {
                val renderTheme = theme()
                val stamps = regionFiles.map { path -> File(path).let { MapFileStamp(it.absolutePath, it.length(), it.lastModified()) } }
                val name = OfflineTiles.sourceName(OfflineTiles.fingerprint(stamps, language, renderTheme.tileSize))
                source = OfflineTileSource(name, renderTheme.tileSize)
                val threads = OfflineTiles.renderThreads(Runtime.getRuntime().availableProcessors())
                // +1 store for osmdroid's pre-cache thread, which renders outside the module's pool.
                val renderer = OfflineRenderer(regionFiles, language, renderTheme, poolSize = threads + 1)
                module = OfflineRegionModule(renderer, source, coverage, writer, threads)
                val keep = source.name()
                maintenance.execute {
                    val purged = writer.purgeStale(keep)
                    if (purged > 0) AppLog.d("Purged $purged stale offline tiles")
                }
            }
            return HybridTileProvider(app, SimpleRegisterReceiver(app), NetworkAvailabliltyCheck(app), mode, module, writer, source)
        }
    }
}
