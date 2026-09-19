package io.treklog.app

import android.app.Application
import io.treklog.app.di.AppContainer
import org.osmdroid.config.Configuration
import java.io.File

class TrekLogApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        configureOsmdroid()
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
