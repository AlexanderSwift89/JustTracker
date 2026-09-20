package com.justtracker.app.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Abstraction over the platform location provider so the service can be tested with fakes. */
interface LocationSource {
    /** Emits every location fix while collected; cancelling the collector stops updates. */
    fun updates(intervalMs: Long): Flow<Location>

    /** Best-effort last known location for initial map centering; null when unavailable. */
    suspend fun lastKnown(): Location?
}

/**
 * Pure provider choice (ADR-14): no Google Play Services, only what the platform ships.
 * Kept free of Android types so it can be unit-tested.
 */
object ProviderPolicy {
    /** Platform fused provider (AOSP, API 31+) — not the GMS one. */
    const val FUSED = "fused"
    const val GPS = "gps"
    const val NETWORK = "network"
    const val PASSIVE = "passive"

    /** Provider to request continuous updates from, or null when the device has none. */
    fun pick(sdkInt: Int, available: Set<String>): String? = when {
        sdkInt >= 31 && FUSED in available -> FUSED
        GPS in available -> GPS
        NETWORK in available -> NETWORK
        else -> null
    }

    /** Providers to consult for a last known fix, most trustworthy first. */
    fun lastKnownOrder(sdkInt: Int): List<String> = buildList {
        if (sdkInt >= 31) add(FUSED)
        add(GPS)
        add(NETWORK)
        add(PASSIVE)
    }
}

/**
 * `android.location.LocationManager` implementation — works on devices without Google services.
 * Callers must hold ACCESS_FINE_LOCATION; a SecurityException (permission revoked mid-recording)
 * closes the flow so the service can react to it exactly as before.
 */
class PlatformLocationSource(context: Context) : LocationSource {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun provider(): String? = ProviderPolicy.pick(Build.VERSION.SDK_INT, manager.allProviders.toSet())

    @SuppressLint("MissingPermission")
    override fun updates(intervalMs: Long): Flow<Location> = callbackFlow {
        val provider = provider()
        if (provider == null) {
            close(IllegalStateException("No location provider on this device"))
            return@callbackFlow
        }
        val listener = LocationListenerCompat { location -> trySend(location) }
        val request = LocationRequestCompat.Builder(intervalMs)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMinUpdateDistanceMeters(0f)
            .build()
        try {
            LocationManagerCompat.requestLocationUpdates(
                manager, provider, request, ContextCompat.getMainExecutor(appContext), listener,
            )
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        } catch (e: IllegalArgumentException) {
            // Provider disappeared between allProviders and the request.
            close(e)
            return@callbackFlow
        }
        awaitClose { LocationManagerCompat.removeUpdates(manager, listener) }
    }

    @SuppressLint("MissingPermission")
    override suspend fun lastKnown(): Location? = runCatching {
        val available = manager.allProviders.toSet()
        ProviderPolicy.lastKnownOrder(Build.VERSION.SDK_INT)
            .filter { it in available }
            .mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }.getOrNull()
}
