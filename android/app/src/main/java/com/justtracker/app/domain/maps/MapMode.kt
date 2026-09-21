package com.justtracker.app.domain.maps

/**
 * Where the map takes its tiles from (US-22). Chosen explicitly by the user — in Settings or by
 * confirming a prompt after connectivity changed; the app never flips it silently.
 *
 * - [ONLINE]: OpenStreetMap tiles from the internet plus the on-disk cache; downloaded regions are not used.
 * - [OFFLINE]: downloaded Mapsforge regions only (plus already cached online tiles for uncovered areas);
 *   no network traffic for the map.
 */
enum class MapMode { ONLINE, OFFLINE }

/** Why the app suggests switching the map mode. */
enum class MapModeReason { INTERNET_LOST, INTERNET_RESTORED }

/** A switch the user is asked to confirm. */
data class MapModeSuggestion(val target: MapMode, val reason: MapModeReason)

/**
 * Pure decision: should the user be asked to switch? The controller calls it whenever mode,
 * connectivity or the set of ready regions changes, and remembers the connectivity state the user
 * last declined for so the same situation is not asked about twice.
 */
object MapModeAdvisor {
    fun suggest(
        mode: MapMode,
        online: Boolean,
        hasReadyRegions: Boolean,
        /** Connectivity state (online = true / offline = false) for which the user already answered "keep"; null = none. */
        declinedFor: Boolean?,
    ): MapModeSuggestion? {
        if (declinedFor == online) return null
        return when {
            mode == MapMode.ONLINE && !online && hasReadyRegions -> MapModeSuggestion(MapMode.OFFLINE, MapModeReason.INTERNET_LOST)
            mode == MapMode.OFFLINE && online -> MapModeSuggestion(MapMode.ONLINE, MapModeReason.INTERNET_RESTORED)
            else -> null
        }
    }
}
