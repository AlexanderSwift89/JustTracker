package com.justtracker.app.service

import com.justtracker.app.data.poi.PoiResult
import com.justtracker.app.di.AppContainer
import com.justtracker.app.domain.poi.GeoCell
import com.justtracker.app.domain.poi.PoiProximity
import com.justtracker.app.util.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Automatic "audio guide" (option A of the POI feature): while a recording is running and the
 * user has enabled it in Settings, reads a short summary of a place aloud the first time the user
 * comes within [PoiProximity.ANNOUNCE_RADIUS_M] of it. Lives in the application scope so it keeps
 * working with the screen off, as long as the foreground service keeps the process alive.
 *
 * Disabled by default (docs/03_prd.md US-17).
 */
class PoiAnnouncer(private val container: AppContainer) {
    private var job: Job? = null
    private val announced = HashSet<String>()
    private var wasRunning = false

    fun start() {
        if (job != null) return
        job = container.appScope.launch {
            combine(container.settingsFlow, container.trackingController.live) { s, live -> (s.poiEnabled && s.poiAutoSpeak) to live }
                .conflate()
                .collect { (enabled, live) ->
                    if (live.serviceRunning && !wasRunning) announced.clear()
                    wasRunning = live.serviceRunning
                    if (!enabled || !live.serviceRunning) return@collect
                    val lat = live.lastLat ?: return@collect
                    val lon = live.lastLon ?: return@collect
                    runCatching { check(lat, lon) }.onFailure { AppLog.w("PoiAnnouncer: ${it.javaClass.simpleName}") }
                }
        }
    }

    private suspend fun check(lat: Double, lon: Double) {
        val repo = container.poiRepository
        val cell = GeoCell.of(lat, lon)
        val pois = repo.cachedAround(cell) ?: when (val r = repo.aroundCell(cell)) {
            is PoiResult.Found -> r.pois
            PoiResult.Unavailable -> return
        }
        val poi = PoiProximity.nextToAnnounce(pois, lat, lon, announced) ?: return
        announced += poi.id
        val summary = repo.summary(poi.wikipedia)
        val text = if (summary != null) "${poi.name}. ${summary.spokenIntro()}" else poi.name
        container.tts.speak(id = poi.id, text = text, lang = summary?.lang ?: poi.wikipedia.lang, flush = false)
    }
}
