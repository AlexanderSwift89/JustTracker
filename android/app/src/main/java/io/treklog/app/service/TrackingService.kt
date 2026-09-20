package io.treklog.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteFullException
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.treklog.app.TrekLogApplication
import io.treklog.app.di.AppContainer
import io.treklog.app.domain.geo.FilterResult
import io.treklog.app.domain.geo.LocationFilter
import io.treklog.app.domain.geo.Sample
import io.treklog.app.domain.model.ActivityType
import io.treklog.app.domain.model.Track
import io.treklog.app.domain.model.TrackPoint
import io.treklog.app.domain.model.TrackStatus
import io.treklog.app.domain.stats.IncrementalStats
import io.treklog.app.util.AppLog
import io.treklog.app.util.TimeFormat
import io.treklog.app.util.UnitFormatter
import io.treklog.app.util.labelRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Foreground service (type=location) that records GPS points into Room while the user has an
 * active track. All persistent state is in the database; see docs/05_architecture.md §4.
 *
 * Lifecycle: START → (PAUSE ⇄ RESUME)* → STOP. A null intent (START_STICKY restart) or
 * ACTION_RECOVER re-attaches to whichever track is still RECORDING/PAUSED in the DB.
 */
class TrackingService : Service() {

    private class Session(
        var track: Track,
        val stats: IncrementalStats,
        var segment: Int,
        var pausedAt: Long?,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer
    private lateinit var notification: TrackingNotification
    private var session: Session? = null
    private var locationJob: Job? = null
    private var lastNotificationUpdate = 0L
    private var filter = LocationFilter()

    override fun onCreate() {
        super.onCreate()
        container = (application as TrekLogApplication).container
        notification = TrackingNotification(this)
        notification.ensureChannel()
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
        container.trackingController.update { it.copy(serviceRunning = false, currentSpeedMps = 0f) }
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
            filter = LocationFilter(maxAccuracyM = settings.maxAccuracyM.toFloat())
            val now = System.currentTimeMillis()
            val track = container.trackRepository.createTrack(autoName(ActivityType.UNKNOWN, now), now)
            session = Session(track, IncrementalStats(), segment = 0, pausedAt = null)
            startLocationUpdates()
        }
    }

    private fun handlePause() {
        val s = session ?: return
        if (s.track.status != TrackStatus.RECORDING) return
        locationJob?.cancel()
        locationJob = null
        s.pausedAt = System.currentTimeMillis()
        s.stats.breakSegment()
        scope.launch {
            s.track = s.track.copy(status = TrackStatus.PAUSED)
            container.trackRepository.updateTrack(s.track)
            container.trackingController.update { it.copy(currentSpeedMps = 0f) }
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
            s.segment += 1
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
        filter = LocationFilter(maxAccuracyM = settings.maxAccuracyM.toFloat())
        val stats = IncrementalStats(
            distanceM = track.distanceM,
            movingTimeMs = track.movingTimeMs,
            maxSpeedMps = track.maxSpeedMps,
            pointCount = track.pointCount,
        )
        // Continue in a fresh segment so the gap during downtime is not drawn as a straight line.
        val segment = container.trackRepository.maxSegment(track.id) + 1
        val s = Session(track, stats, segment, pausedAt = if (track.status == TrackStatus.PAUSED) System.currentTimeMillis() else null)
        session = s
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

    private suspend fun onLocation(loc: Location) {
        val s = session ?: return
        val sample = Sample(
            timestamp = loc.time,
            lat = loc.latitude,
            lon = loc.longitude,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
        )
        container.trackingController.update {
            it.copy(
                lastFixAt = System.currentTimeMillis(),
                lastLat = if (sample.accuracyM <= POSITION_MARKER_ACCURACY_M) sample.lat else it.lastLat,
                lastLon = if (sample.accuracyM <= POSITION_MARKER_ACCURACY_M) sample.lon else it.lastLon,
            )
        }
        when (val result = filter.evaluate(s.stats.lastSample, sample)) {
            is FilterResult.Rejected -> AppLog.geo { "rejected ${result.reason} acc=${sample.accuracyM} t=${sample.timestamp} lat=${sample.lat} lon=${sample.lon} prev=${s.stats.lastSample?.lat}" }
            is FilterResult.Accepted -> {
                s.stats.accept(sample, result.distanceM, result.speedMps)
                val point = TrackPoint(
                    trackId = s.track.id,
                    segment = s.segment,
                    timestamp = sample.timestamp,
                    lat = sample.lat,
                    lon = sample.lon,
                    altitudeM = if (loc.hasAltitude()) loc.altitude else null,
                    accuracyM = sample.accuracyM,
                    speedMps = result.speedMps,
                    bearingDeg = if (loc.hasBearing()) loc.bearing else null,
                )
                s.track = s.track.copy(
                    distanceM = s.stats.distanceM,
                    movingTimeMs = s.stats.movingTimeMs,
                    maxSpeedMps = s.stats.maxSpeedMps,
                    avgSpeedMps = s.stats.avgSpeedMps,
                    pointCount = s.stats.pointCount,
                )
                try {
                    container.trackRepository.addPoint(point, s.track)
                } catch (e: SQLiteFullException) {
                    AppLog.e("Storage full, finishing track", e)
                    handleStop()
                    return
                }
                container.trackingController.update { it.copy(currentSpeedMps = s.stats.currentSpeedMps) }
                if (s.stats.pointCount >= MAX_POINTS_PER_TRACK) {
                    AppLog.w("Max points reached, finishing track")
                    handleStop()
                    return
                }
                refreshNotification(force = false)
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    @SuppressLint("InlinedApi") // ServiceCompat handles the type on older APIs
    private fun goForeground(paused: Boolean): Boolean {
        val s = session
        val n = notification.build(
            paused = paused,
            startedAt = s?.track?.startedAt ?: System.currentTimeMillis(),
            distanceM = s?.stats?.distanceM ?: 0.0,
            speedMps = s?.stats?.currentSpeedMps?.toDouble() ?: 0.0,
            formatter = formatter(),
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
            distanceM = s.stats.distanceM,
            speedMps = s.stats.currentSpeedMps.toDouble(),
            formatter = formatter(),
        )
        try {
            NotificationManagerCompat.from(this).notify(TrackingNotification.NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            AppLog.w("Notification permission missing", e)
        }
    }

    private var cachedFormatter: UnitFormatter? = null
    private fun formatter(): UnitFormatter =
        cachedFormatter ?: UnitFormatter(this, container.cachedSettings.units).also { cachedFormatter = it }

    private fun autoName(type: ActivityType, startedAt: Long): String =
        getString(type.labelRes()) + " · " + TimeFormat.dateShort(startedAt)

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START = "io.treklog.app.action.START"
        const val ACTION_PAUSE = "io.treklog.app.action.PAUSE"
        const val ACTION_RESUME = "io.treklog.app.action.RESUME"
        const val ACTION_STOP = "io.treklog.app.action.STOP"
        const val ACTION_RECOVER = "io.treklog.app.action.RECOVER"

        const val LOCATION_INTERVAL_MS = 1000L
        const val NOTIFICATION_THROTTLE_MS = 3000L
        const val MAX_POINTS_PER_TRACK = 100_000
        const val POSITION_MARKER_ACCURACY_M = 100f
    }
}
