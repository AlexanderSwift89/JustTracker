package com.justtracker.app.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.domain.model.AppLanguage
import com.justtracker.app.domain.model.AppSettings
import com.justtracker.app.domain.model.ThemeMode
import com.justtracker.app.domain.model.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val UNITS = stringPreferencesKey("units")
        val THEME = stringPreferencesKey("theme")
        val MAX_ACCURACY = intPreferencesKey("max_accuracy_m")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val POI_ENABLED = booleanPreferencesKey("poi_enabled")
        val POI_AUTO_SPEAK = booleanPreferencesKey("poi_auto_speak")
        val LANGUAGE = stringPreferencesKey("language")
        val MAPS_WIFI_ONLY = booleanPreferencesKey("maps_wifi_only")
        val MAP_MODE = stringPreferencesKey("map_mode")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            units = p[Keys.UNITS]?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() } ?: UnitSystem.METRIC,
            theme = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            maxAccuracyM = p[Keys.MAX_ACCURACY] ?: 50,
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: false,
            onboardingDone = p[Keys.ONBOARDING_DONE] ?: false,
            poiEnabled = p[Keys.POI_ENABLED] ?: true,
            poiAutoSpeak = p[Keys.POI_AUTO_SPEAK] ?: false,
            language = AppLanguage.fromTag(p[Keys.LANGUAGE]),
            mapsWifiOnly = p[Keys.MAPS_WIFI_ONLY] ?: true,
            mapMode = p[Keys.MAP_MODE]?.let { runCatching { MapMode.valueOf(it) }.getOrNull() } ?: MapMode.ONLINE,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setUnits(units: UnitSystem) = context.settingsStore.edit { it[Keys.UNITS] = units.name }
    suspend fun setTheme(theme: ThemeMode) = context.settingsStore.edit { it[Keys.THEME] = theme.name }
    suspend fun setMaxAccuracy(meters: Int) = context.settingsStore.edit { it[Keys.MAX_ACCURACY] = meters.coerceIn(10, 100) }
    suspend fun setKeepScreenOn(on: Boolean) = context.settingsStore.edit { it[Keys.KEEP_SCREEN_ON] = on }
    suspend fun setOnboardingDone() = context.settingsStore.edit { it[Keys.ONBOARDING_DONE] = true }
    suspend fun setPoiEnabled(on: Boolean) = context.settingsStore.edit { it[Keys.POI_ENABLED] = on }
    suspend fun setPoiAutoSpeak(on: Boolean) = context.settingsStore.edit { it[Keys.POI_AUTO_SPEAK] = on }
    suspend fun setLanguage(language: AppLanguage) = context.settingsStore.edit { it[Keys.LANGUAGE] = language.tag }
    suspend fun setMapsWifiOnly(on: Boolean) = context.settingsStore.edit { it[Keys.MAPS_WIFI_ONLY] = on }
    suspend fun setMapMode(mode: MapMode) = context.settingsStore.edit { it[Keys.MAP_MODE] = mode.name }
}
