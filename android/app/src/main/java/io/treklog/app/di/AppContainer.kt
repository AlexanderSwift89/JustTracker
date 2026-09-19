package io.treklog.app.di

import android.content.Context
import io.treklog.app.data.db.TrekLogDatabase
import io.treklog.app.data.location.FusedLocationSource
import io.treklog.app.data.location.LocationSource
import io.treklog.app.data.repo.SettingsRepository
import io.treklog.app.data.repo.TrackRepository
import io.treklog.app.domain.model.AppSettings
import io.treklog.app.service.TrackingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Manual dependency graph (ADR-03). One instance per process, owned by [io.treklog.app.TrekLogApplication]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: TrekLogDatabase by lazy { TrekLogDatabase.build(appContext) }
    val trackRepository: TrackRepository by lazy { TrackRepository(database.trackDao()) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val locationSource: LocationSource by lazy { FusedLocationSource(appContext) }
    val trackingController: TrackingController by lazy { TrackingController(appContext) }

    /** Hot copy of settings for non-suspending callers (notification formatting). */
    val settingsFlow: StateFlow<AppSettings> by lazy {
        settingsRepository.settings.stateIn(appScope, SharingStarted.Eagerly, AppSettings())
    }
    val cachedSettings: AppSettings get() = settingsFlow.value
}
