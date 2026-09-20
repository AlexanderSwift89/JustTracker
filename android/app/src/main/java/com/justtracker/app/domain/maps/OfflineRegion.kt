package com.justtracker.app.domain.maps

import com.justtracker.app.domain.model.AppLanguage

/**
 * Region lifecycle (US-19/US-20):
 *
 * ```
 * (absent) --download--> QUEUED --running--> DOWNLOADING --success--> VERIFYING --header ok--> READY
 * QUEUED / DOWNLOADING --failed / cancelled--> ERROR / (absent)
 * VERIFYING --corrupt--> ERROR            ERROR --retry--> QUEUED
 * READY --delete--> (absent)              (absent) --import--> VERIFYING --ok--> READY
 * ```
 */
enum class RegionStatus { QUEUED, DOWNLOADING, VERIFYING, READY, ERROR }

enum class RegionError { NO_SPACE, NETWORK, CORRUPT, LOST, UNKNOWN }

enum class RegionSource { CATALOG, IMPORT }

/** Events that move a region between statuses; used by [RegionTransitions]. */
enum class RegionEvent { DOWNLOAD, RUNNING, PAUSED, SUCCESS, FAILED, CANCEL, VERIFIED, CORRUPT, RETRY, DELETE }

/** Pure state machine so the store cannot drift into impossible states; null = illegal transition. */
object RegionTransitions {
    fun next(current: RegionStatus?, event: RegionEvent): RegionStatus? = when (event) {
        RegionEvent.DOWNLOAD -> if (current == null) RegionStatus.QUEUED else null
        RegionEvent.RETRY -> if (current == RegionStatus.ERROR) RegionStatus.QUEUED else null
        RegionEvent.RUNNING -> if (current == RegionStatus.QUEUED || current == RegionStatus.DOWNLOADING) RegionStatus.DOWNLOADING else null
        RegionEvent.PAUSED -> if (current == RegionStatus.DOWNLOADING || current == RegionStatus.QUEUED) RegionStatus.QUEUED else null
        RegionEvent.SUCCESS -> if (current == RegionStatus.QUEUED || current == RegionStatus.DOWNLOADING) RegionStatus.VERIFYING else null
        RegionEvent.FAILED -> if (current == RegionStatus.QUEUED || current == RegionStatus.DOWNLOADING) RegionStatus.ERROR else null
        RegionEvent.VERIFIED -> if (current == RegionStatus.VERIFYING) RegionStatus.READY else null
        RegionEvent.CORRUPT -> if (current == RegionStatus.VERIFYING) RegionStatus.ERROR else null
        RegionEvent.CANCEL, RegionEvent.DELETE -> null // row removed
    }
}

/** A region as shown in the UI and consumed by the map. */
data class OfflineRegion(
    val id: String,
    val nameEn: String,
    val nameRu: String,
    /** Absolute path of the `.map` file once READY; null otherwise. */
    val file: String?,
    val sizeBytes: Long,
    val box: LatLonBox,
    val source: RegionSource,
    val status: RegionStatus,
    /** 0..1 while DOWNLOADING; null when unknown or not downloading. */
    val progress: Float? = null,
    /** True while the system download manager waits for a (Wi-Fi) network. */
    val waitingForNetwork: Boolean = false,
    val error: RegionError? = null,
    val updatedAt: Long = 0L,
) {
    fun name(language: AppLanguage): String = when (language) {
        AppLanguage.RU -> nameRu.ifBlank { nameEn }
        AppLanguage.EN -> nameEn.ifBlank { nameRu }
    }

    fun coverage(): RegionCoverage? = file?.takeIf { status == RegionStatus.READY }?.let { RegionCoverage(id, it, box) }
}
