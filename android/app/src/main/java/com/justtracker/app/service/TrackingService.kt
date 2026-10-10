package com.justtracker.app.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteFullException
import android.location.Location
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.justtracker.app.JustTrackerApplication
import com.justtracker.app.di.AppContainer
import com.justtracker.app.domain.geo.Sample
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.AppSettings
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.model.TrackStatus
import com.justtracker.app.domain.recording.Fix
import com.justtracker.app.domain.recording.RecorderStore
import com.justtracker.app.domain.recording.StopReason
import com.justtracker.app.domain.recording.TrackRecorder
import com.justtracker.app.domain.recording.TrackTotals
import com.justtracker.app.domain.stats.AccelerationTrace
import com.justtracker.app.util.AppLog
import com.justtracker.app.util.TimeFormat
import com.justtracker.app.util.AppLocale
import com.justtracker.app.util.Permissions
import com.justtracker.app.util.UnitFormatter
import com.justtracker.app.util.labelRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service (type=location) that records GPS points into Room while the user has an
 * active track. All persistent state is in the database; see docs/05_architecture.md §4.
 *
 * Lifecycle: START → (PAUSE ⇄ RESUME)* → STOP. A null intent (START_STICKY restart) or
 * ACTION_RECOVER re-attaches to whichever track is still RECORDING/PAUSED in the DB.
 */
class TrackingService : Service() {

    /** The active recording: its track row as last written and the recorder that applies the recording rules. */
    private class Session(var track: Track, val recorder: TrackRecorder, var pausedAt: Long?)

    /** Room behind the recorder: a point and the track's running totals in one transaction. */
    private inner class Store : RecorderStore {
        override suspend fun insert(point: TrackPoint, totals: TrackTotals): Boolean {
            val s = session ?: return true
            s.track = s.track.copy(
                distanceM = totals.distanceM,
                movingTimeMs = totals.movingTimeMs,
                maxSpeedMps = totals.maxSpeedMps,
                avgSpeedMps = totals.avgSpeedMps,
                pointCount = totals.pointCount,
            )
            return try {
                container.trackRepository.addPoint(point, s.track)
                true
            } catch (e: SQLiteFullException) {
                AppLog.e("Storage full, finishing track", e)
                false
            }
        }

        override suspend fun deleteSegmentIfShort(trackId: Long, segment: Int, maxPoints: Int): Int =
            container.trackRepository.deleteSegmentIfShort(trackId, segment, maxPoints)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer
    /** Resources in the app language: on API < 33 a Service context still follows the device locale. */
    private lateinit var localized: Context
    private lateinit var notification: TrackingNotification
    private lateinit var formatter: UnitFormatter
    private var session: Session? = null
    private var locationJob: Job? = null
    private var lastNotificationUpdate = 0L

    override fun onCreate() {
        super.onCreate()
        container = (application as JustTrackerApplication).container
        applyLocaleAndUnits(container.cachedSettings)
        // The notification follows a change of units or language at once, also in the middle of a recording (D-26).
        // The first value also replaces the defaults a cold start may have seen before DataStore was read.
        scope.launch {
            container.settingsFlow.map { it.units to it.language }.distinctUntilChanged().collect {
                applyLocaleAndUnits(container.cachedSettings)
                if (session != null) refreshNotification(force = true)
            }
        }
    }

    /** Context, notification builder and formatter are made once per language and units, not on every update. */
    private fun applyLocaleAndUnits(settings: AppSettings) {
        localized = AppLocale.localized(this, settings)
        notification = TrackingNotification(localized).also { it.ensureChannel() }
        formatter = UnitFormatter(localized, settings.units)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
            ACTION_RECOVER, null -> handleRecover()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        locationJob?.cancel()
        scope.cancel()
        container.trackingController.update {
            it.copy(serviceRunning = false, currentSpeedMps = 0f, acceleration = null, accelerationTrace = AccelerationTrace.EMPTY)
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- actions

    private fun handleStart() {
        if (!hasLocationPermission()) {
            container.trackingController.update { it.copy(permissionLost = true) }
            stopSelf()
            return
        }
        if (!goForeground(paused = false)) return
        scope.launch {
            if (session != null) return@launch
            val existing = container.trackRepository.getActiveTrack()
            if (existing != null) {
                attach(existing)
                return@launch
            }
            val settings = container.settingsRepository.current()
            val now = System.currentTimeMillis()
            val track = container.trackRepository.createTrack(autoName(ActivityType.UNKNOWN, now), now)
            session = Session(track, recorder(track.id, startSegment = 0, TrackTotals(), settings.maxAccuracyM), pausedAt = null)
            startLocationUpdates()
        }
    }

    private fun handlePause() {
        val s = session ?: return
        if (s.track.status != TrackStatus.RECORDING) return
        locationJob?.cancel()
        locationJob = null
        s.pausedAt = System.currentTimeMillis()
        s.recorder.pause()
        scope.launch {
            s.track = s.track.copy(status = TrackStatus.PAUSED)
            container.trackRepository.updateTrack(s.track)
            container.trackingController.update { it.copy(currentSpeedMps = 0f, acceleration = null) }
            refreshNotification(force = true)
        }
    }

    private fun handleResume() {
        val s = session
        if (s == null) {
            handleRecover()
            return
        }
        if (s.track.status != TrackStatus.PAUSED) return
        if (!goForeground(paused = false)) return
        scope.launch {
            val now = System.currentTimeMillis()
            val pausedFor = s.pausedAt?.let { now - it } ?: 0L
            s.pausedAt = null
            s.recorder.resume()
            s.track = s.track.copy(status = TrackStatus.RECORDING, pausedTimeMs = s.track.pausedTimeMs + pausedFor)
            container.trackRepository.updateTrack(s.track)
            startLocationUpdates()
            refreshNotification(force = true)
        }
    }

    private fun handleStop() {
        locationJob?.cancel()
        locationJob = null
        val s = session
        scope.launch {
            val now = System.currentTimeMillis()
            val active = s?.track ?: container.trackRepository.getActiveTrack()
            if (active != null) {
                val extraPaused = s?.pausedAt?.let { now - it } ?: 0L
                if (extraPaused > 0 && s != null) {
                    s.track = s.track.copy(pausedTimeMs = s.track.pausedTimeMs + extraPaused)
                    container.trackRepository.updateTrack(s.track)
                }
                val finished = container.trackRepository.finish(active.id, now) { type -> autoName(type, active.startedAt) }
                container.trackingController.update {
                    it.copy(lastFinishedTrackId = finished?.id, lastFinishDiscarded = finished == null, finishSerial = it.finishSerial + 1)
                }
            }
            session = null
            ServiceCompat.stopForeground(this@TrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Re-attach to the active track after the process was killed or the app re-launched the service. */
    private fun handleRecover() {
        if (session != null) return
        if (!hasLocationPermission()) {
            container.trackingController.update { it.copy(permissionLost = true) }
            stopSelf()
            return
        }
        scope.launch {
            val active = container.trackRepository.getActiveTrack()
            if (active == null) {
                stopSelf()
                return@launch
            }
            if (!goForeground(paused = active.status == TrackStatus.PAUSED)) return@launch
            attach(active)
        }
    }

    private suspend fun attach(track: Track) {
        val settings = container.settingsRepository.current()
        val totals = TrackTotals(track.distanceM, track.movingTimeMs, track.maxSpeedMps, track.avgSpeedMps, track.pointCount)
        // Continue in a fresh segment so the gap during downtime is not drawn as a straight line.
        val segment = container.trackRepository.maxSegment(track.id) + 1
        val pausedAt = if (track.status == TrackStatus.PAUSED) System.currentTimeMillis() else null
        session = Session(track, recorder(track.id, segment, totals, settings.maxAccuracyM), pausedAt)
        if (track.status == TrackStatus.RECORDING) startLocationUpdates()
        refreshNotification(force = true)
    }

    // ---------------------------------------------------------------- location

    private fun startLocationUpdates() {
        locationJob?.cancel()
        container.trackingController.update { it.copy(serviceRunning = true, permissionLost = false) }
        locationJob = scope.launch {
            container.locationSource.updates(LOCATION_INTERVAL_MS)
                .catch { e ->
                    if (e is SecurityException) {
                        AppLog.w("Location permission revoked during recording")
                        container.trackingController.update { it.copy(permissionLost = true) }
                    } else {
                        AppLog.e("Location stream failed", e)
                    }
                }
                .collect { onLocation(it) }
        }
    }

    private fun recorder(trackId: Long, startSegment: Int, totals: TrackTotals, maxAccuracyM: Int) =
        TrackRecorder(trackId, startSegment, totals, maxAccuracyM.toFloat(), Store(), log = AppLog::geo)

    /** One fix through the recording rules ([TrackRecorder]), then one update of the live state. */
    private suspend fun onLocation(loc: Location) {
        val s = session ?: return
        val outcome = s.recorder.onFix(loc.toFix())
        val now = System.currentTimeMillis()
        container.trackingController.update {
            it.copy(
                // Coarse or repeated fixes alone do not count: the panel then says "searching GPS" (D-39).
                lastFixAt = if (outcome.usable) now else it.lastFixAt,
                lastLat = outcome.marker?.lat ?: it.lastLat,
                lastLon = outcome.marker?.lon ?: it.lastLon,
                currentSpeedMps = outcome.speedMps ?: it.currentSpeedMps,
                acceleration = outcome.acceleration,
                accelerationAt = if (outcome.acceleration != null) now else it.accelerationAt,
                accelerationTrace = outcome.accelerationTrace,
            )
        }
        when (outcome.stop) {
            StopReason.POINT_LIMIT -> {
                AppLog.w("Max points reached, finishing track")
                handleStop()
            }
            StopReason.STORAGE_FULL -> handleStop()
            null -> if (outcome.stored > 0) refreshNotification(force = false)
        }
    }

    private fun Location.toFix() = Fix(
        sample = Sample(
            timestamp = time,
            lat = latitude,
            lon = longitude,
            accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
            speedMps = if (hasSpeed()) speed else null,
        ),
        // Monotonic: unlike the UTC time it never steps when the clock is corrected.
        monotonicMs = elapsedRealtimeNanos / 1_000_000,
        speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
        altitudeM = if (hasAltitude()) altitude else null,
        verticalAccuracyM = if (hasAltitude() && hasVerticalAccuracy()) verticalAccuracyMeters else null,
        bearingDeg = if (hasBearing()) bearing else null,
    )

    // ---------------------------------------------------------------- helpers

    @SuppressLint("InlinedApi") // ServiceCompat handles the type on older APIs
    private fun goForeground(paused: Boolean): Boolean {
        val s = session
        val n = notification.build(
            paused = paused,
            startedAt = s?.track?.startedAt ?: System.currentTimeMillis(),
            distanceM = s?.recorder?.totals?.distanceM ?: 0.0,
            speedMps = if (s == null) 0.0 else container.trackingController.live.value.currentSpeedMps.toDouble(),
            formatter = formatter,
        )
        return try {
            ServiceCompat.startForeground(this, TrackingNotification.NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            container.trackingController.update { it.copy(serviceRunning = true) }
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+) or SecurityException on API 34+ without permission.
            AppLog.e("startForeground failed", e)
            container.trackingController.update { it.copy(serviceRunning = false) }
            stopSelf()
            false
        }
    }

    private suspend fun refreshNotification(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationUpdate < NOTIFICATION_THROTTLE_MS) return
        lastNotificationUpdate = now
        val s = session ?: return
        if (!hasNotificationPermission()) return
        val n = notification.build(
            paused = s.track.status == TrackStatus.PAUSED,
            startedAt = s.track.startedAt,
            distanceM = s.recorder.totals.distanceM,
            speedMps = container.trackingController.live.value.currentSpeedMps.toDouble(),
            formatter = formatter,
        )
        try {
            NotificationManagerCompat.from(this).notify(TrackingNotification.NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            AppLog.w("Notification permission missing", e)
        }
    }

    private fun autoName(type: ActivityType, startedAt: Long): String =
        localized.getString(type.labelRes()) + " · " + TimeFormat.dateShort(startedAt, AppLocale.current(container.cachedSettings))

    private fun hasLocationPermission() = Permissions.hasLocation(this)

    private fun hasNotificationPermission() = Permissions.hasNotifications(this)

    companion object {
        const val ACTION_START = "com.justtracker.app.action.START"
        const val ACTION_PAUSE = "com.justtracker.app.action.PAUSE"
        const val ACTION_RESUME = "com.justtracker.app.action.RESUME"
        const val ACTION_STOP = "com.justtracker.app.action.STOP"
        const val ACTION_RECOVER = "com.justtracker.app.action.RECOVER"

        const val LOCATION_INTERVAL_MS = 1000L
        const val NOTIFICATION_THROTTLE_MS = 3000L
    }
}
