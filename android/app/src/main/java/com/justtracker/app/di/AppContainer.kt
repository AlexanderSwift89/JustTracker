package com.justtracker.app.di

import android.content.Context
import com.justtracker.app.data.db.JustTrackerDatabase
import com.justtracker.app.data.location.FusedLocationSource
import com.justtracker.app.data.location.LocationSource
import com.justtracker.app.data.poi.PoiRepository
import com.justtracker.app.data.tts.TtsSpeaker
import com.justtracker.app.data.repo.SettingsRepository
import com.justtracker.app.data.repo.TrackRepository
import com.justtracker.app.domain.model.AppSettings
import com.justtracker.app.service.PoiAnnouncer
import com.justtracker.app.service.TrackingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Manual dependency graph (ADR-03). One instance per process, owned by [com.justtracker.app.JustTrackerApplication]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: JustTrackerDatabase by lazy { JustTrackerDatabase.build(appContext) }
    val trackRepository: TrackRepository by lazy { TrackRepository(database.trackDao()) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val locationSource: LocationSource by lazy { FusedLocationSource(appContext) }
    val trackingController: TrackingController by lazy { TrackingController(appContext) }
    val poiRepository: PoiRepository by lazy { PoiRepository() }
    val tts: TtsSpeaker by lazy { TtsSpeaker(appContext) }
    val poiAnnouncer: PoiAnnouncer by lazy { PoiAnnouncer(this) }

    /** Hot copy of settings for non-suspending callers (notification formatting). */
    val settingsFlow: StateFlow<AppSettings> by lazy {
        settingsRepository.settings.stateIn(appScope, SharingStarted.Eagerly, AppSettings())
    }
    val cachedSettings: AppSettings get() = settingsFlow.value
}
