package com.justtracker.app.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Abstraction over the platform location provider so the service can be tested with fakes. */
interface LocationSource {
    /** Emits every location fix while collected; cancelling the collector stops updates. */
    fun updates(intervalMs: Long): Flow<Location>

    /** Best-effort last known location for initial map centering; null when unavailable. */
    suspend fun lastKnown(): Location?
}

/**
 * Fused Location Provider implementation. Callers must hold ACCESS_FINE_LOCATION; the
 * SecurityException is deliberately not caught here so the service can react to revoked permission.
 */
class FusedLocationSource(context: Context) : LocationSource {
    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    override fun updates(intervalMs: Long): Flow<Location> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) trySend(loc)
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    @SuppressLint("MissingPermission")
    override suspend fun lastKnown(): Location? = runCatching { client.lastLocation.await() }.getOrNull()
}
