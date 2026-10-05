package com.justtracker.app.data.maps

import android.content.Context
import com.justtracker.app.data.maps.render.HybridTileProvider
import com.justtracker.app.data.maps.render.OfflineRenderTheme
import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.domain.model.AppSettings
import com.justtracker.app.util.AppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/** What a map's tile provider is built from (ADR-16, ADR-17): a change of any of it builds a new provider. */
data class TileConfig(val mode: MapMode, val regionFiles: List<String>, val language: String)

/**
 * The tile side of every map: the provider configuration from the settings and the READY regions, and the factory of
 * providers. The map widget takes it instead of reaching into the AppContainer.
 */
class MapTiles(
    context: Context,
    settings: StateFlow<AppSettings>,
    private val regions: OfflineRegionStore,
    private val renderTheme: () -> OfflineRenderTheme,
    scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    val config: StateFlow<TileConfig> = combine(settings, regions.readyCoverage) { s, coverage -> configOf(s, coverage.map { it.file }) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, configOf(settings.value, regions.readyCoverage.value.map { it.file }))

    /** A provider for [config]; the region files only count in OFFLINE mode (ONLINE never consults them). */
    fun provider(config: TileConfig): HybridTileProvider =
        HybridTileProvider.create(appContext, config.mode, config.regionFiles, config.language, renderTheme) { regions.readyCoverage.value }

    private fun configOf(s: AppSettings, files: List<String>) =
        TileConfig(s.mapMode, if (s.mapMode == MapMode.OFFLINE) files else emptyList(), AppLocale.current(s).language)
}
