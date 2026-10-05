package com.justtracker.app.data.repo

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.domain.model.AppLanguage
import com.justtracker.app.domain.model.AppSettings
import com.justtracker.app.domain.model.ThemeMode
import com.justtracker.app.domain.model.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Drops the preferences of features that no longer exist, so nothing about them stays on the device:
 * "Places nearby" and its auto-announcements were removed in 1.1.2 (SEC-14, ADR-24).
 */
internal object RemovedFeatureKeysMigration : DataMigration<Preferences> {
    val removedKeys = listOf(booleanPreferencesKey("poi_enabled"), booleanPreferencesKey("poi_auto_speak"))

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = removedKeys.any { it in currentData }

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply { removedKeys.forEach { remove(it) } }.toPreferences()

    override suspend fun cleanUp() = Unit
}

/**
 * App settings in a Preferences DataStore. The store is created once per process by the AppContainer (file
 * `datastore/settings.preferences_pb`, with [RemovedFeatureKeysMigration]); tests pass their own.
 */
class SettingsRepository(private val store: DataStore<Preferences>) {
    private object Keys {
        val UNITS = stringPreferencesKey("units")
        val THEME = stringPreferencesKey("theme")
        val MAX_ACCURACY = intPreferencesKey("max_accuracy_m")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val LANGUAGE = stringPreferencesKey("language")
        val MAPS_WIFI_ONLY = booleanPreferencesKey("maps_wifi_only")
        val MAP_MODE = stringPreferencesKey("map_mode")
        val SHOW_ACCELERATION = booleanPreferencesKey("show_acceleration")
        val ACCELERATION_HINT_SHOWN = booleanPreferencesKey("acceleration_hint_shown")
    }

    // An unreadable file (I/O error) gives the defaults instead of an exception in every screen; corruption is
    // handled by DataStore itself.
    val settings: Flow<AppSettings> = store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }.map { p ->
        AppSettings(
            units = p[Keys.UNITS]?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() } ?: UnitSystem.METRIC,
            theme = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            maxAccuracyM = p[Keys.MAX_ACCURACY] ?: 50,
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: false,
            onboardingDone = p[Keys.ONBOARDING_DONE] ?: false,
            language = AppLanguage.fromTag(p[Keys.LANGUAGE]),
            mapsWifiOnly = p[Keys.MAPS_WIFI_ONLY] ?: true,
            mapMode = p[Keys.MAP_MODE]?.let { runCatching { MapMode.valueOf(it) }.getOrNull() } ?: MapMode.ONLINE,
            showAcceleration = p[Keys.SHOW_ACCELERATION] ?: false,
            accelerationHintShown = p[Keys.ACCELERATION_HINT_SHOWN] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setUnits(units: UnitSystem) = store.edit { it[Keys.UNITS] = units.name }
    suspend fun setTheme(theme: ThemeMode) = store.edit { it[Keys.THEME] = theme.name }
    suspend fun setMaxAccuracy(meters: Int) = store.edit { it[Keys.MAX_ACCURACY] = meters.coerceIn(10, 100) }
    suspend fun setKeepScreenOn(on: Boolean) = store.edit { it[Keys.KEEP_SCREEN_ON] = on }
    suspend fun setOnboardingDone() = store.edit { it[Keys.ONBOARDING_DONE] = true }
    suspend fun setLanguage(language: AppLanguage) = store.edit { it[Keys.LANGUAGE] = language.tag }
    suspend fun setMapsWifiOnly(on: Boolean) = store.edit { it[Keys.MAPS_WIFI_ONLY] = on }
    suspend fun setMapMode(mode: MapMode) = store.edit { it[Keys.MAP_MODE] = mode.name }
    suspend fun setShowAcceleration(on: Boolean) = store.edit {
        it[Keys.SHOW_ACCELERATION] = on
        it[Keys.ACCELERATION_HINT_SHOWN] = true
    }
    suspend fun setAccelerationHintShown() = store.edit { it[Keys.ACCELERATION_HINT_SHOWN] = true }
}
