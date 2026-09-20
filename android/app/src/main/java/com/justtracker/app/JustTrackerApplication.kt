package com.justtracker.app

import android.app.Application
import com.justtracker.app.di.AppContainer
import kotlinx.coroutines.launch
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.config.Configuration
import java.io.File

class JustTrackerApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        configureOsmdroid()
        // Mapsforge needs its Android graphics factory once per process before any region renders.
        MapsForgeTileSource.createInstance(this)
        // Rows vs files vs DownloadManager may have drifted while the process was dead.
        container.appScope.launch { container.offlineRegionStore.reconcile() }
        // Idle until the user enables auto-announcements and a recording is running.
        container.poiAnnouncer.start()
    }

    /**
     * osmdroid keeps its tile cache inside the app's private cache dir (no storage permission) and
     * identifies itself with the package name as required by the OSM tile usage policy.
     */
    private fun configureOsmdroid() {
        Configuration.getInstance().apply {
            userAgentValue = BuildConfig.APPLICATION_ID
            osmdroidBasePath = File(cacheDir, "osmdroid").also { it.mkdirs() }
            osmdroidTileCache = File(osmdroidBasePath, "tiles").also { it.mkdirs() }
            tileFileSystemCacheMaxBytes = 200L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 150L * 1024 * 1024
        }
    }
}
