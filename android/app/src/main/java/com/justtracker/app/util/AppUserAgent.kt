package com.justtracker.app.util

import com.justtracker.app.BuildConfig

/**
 * The one User-Agent of every request the app makes — Overpass, Wikipedia, OSM tiles and region downloads
 * (docs/05_architecture.md ADR-22). The OSMF tile policy and the Wikimedia API etiquette ask for an
 * identifiable agent with a contact; nothing about the device or the user goes into it (the system
 * DownloadManager would otherwise send the Android release, device model and build id).
 */
object AppUserAgent {
    const val CONTACT_URL = "https://alexanderswift89.github.io/JustTracker"

    val value: String = format(BuildConfig.VERSION_NAME)

    internal fun format(versionName: String): String = "JustTracker/$versionName ($CONTACT_URL)"
}
