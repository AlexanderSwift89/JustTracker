package com.justtracker.app

import android.app.Application
import android.os.StrictMode
import com.justtracker.app.data.export.ExportFiles
import com.justtracker.app.di.AppContainer
import com.justtracker.app.util.AppUserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.osmdroid.config.Configuration
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

class JustTrackerApplication : Application() {
    lateinit var container: AppContainer
        private set

    /**
     * Mapsforge logs through java.util.logging (file names, header problems); release builds keep it silent like
     * android.util.Log (SEC-15). Held in a field: LogManager keeps loggers only weakly, and a collected parent would
     * take its level with it.
     */
    private val mapsforgeLogger: Logger = Logger.getLogger("org.mapsforge")

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode() else mapsforgeLogger.level = Level.OFF
        container = AppContainer(this)
        configureOsmdroid()
        // Mapsforge needs its Android graphics factory once per process before any region renders (ADR-17).
        AndroidGraphicFactory.createInstance(this)
        // Internet reachability feeds the "switch map mode?" prompt (US-22).
        container.connectivity.start()
        // Shared GPX copies older than a day go even if the user never exports again (SEC-11).
        container.appScope.launch(Dispatchers.IO) { ExportFiles.purgeOlderThan(ExportFiles.dir(cacheDir), System.currentTimeMillis()) }
        // Rows vs files vs DownloadManager may have drifted while the process was dead.
        container.appScope.launch { container.offlineRegionStore.reconcile() }
    }

    /**
     * Debug builds log disk and network access on the main thread and leaked closeables (cursors, streams) to logcat
     * (tag StrictMode) — the cheap way to catch a slow main thread before it shows as jank (docs/08_test_plan.md §2).
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build())
    }

    /**
     * osmdroid keeps its tile cache inside the app's private cache dir (no storage permission) and
     * identifies itself with the app name, version and contact as required by the OSM tile usage policy
     * ([AppUserAgent], ADR-22). The cache is
     * shared by downloaded online tiles and tiles rendered from offline regions (ADR-17).
     */
    private fun configureOsmdroid() {
        Configuration.getInstance().apply {
            userAgentValue = AppUserAgent.value
            osmdroidBasePath = File(cacheDir, "osmdroid").also { it.mkdirs() }
            osmdroidTileCache = File(osmdroidBasePath, "tiles").also { it.mkdirs() }
            tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 240L * 1024 * 1024
            // Download diagnostics in debug builds only (logcat tag OsmDroid). isDebugTileProviders stays off:
            // besides logging, osmdroid then paints every tile's border and index over the map.
            isDebugMapTileDownloader = BuildConfig.DEBUG
        }
    }
}
