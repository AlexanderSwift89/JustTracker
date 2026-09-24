package com.justtracker.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
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
import com.justtracker.app.JustTrackerApplication
import com.justtracker.app.di.AppContainer
import com.justtracker.app.domain.geo.FilterResult
import com.justtracker.app.domain.geo.JumpStreak
import com.justtracker.app.domain.geo.LocationFilter
import com.justtracker.app.domain.geo.Sample
import com.justtracker.app.domain.geo.StartCheck
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.model.TrackStatus
import com.justtracker.app.domain.stats.IncrementalStats
import com.justtracker.app.util.AppLog
import com.justtracker.app.util.TimeFormat
import com.justtracker.app.util.AppLocale
import com.justtracker.app.util.UnitFormatter
import com.justtracker.app.util.labelRes
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
        /** First fix of the current segment, recorded once the next fix confirms it (LocationFilter.confirmStart). */
        var pendingStart: PendingFix? = null,
    )

    private class PendingFix(val sample: Sample, val location: Location)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: AppContainer
    /** Resources in the app language: on API < 33 a Service context still follows the device locale. */
    private val localized: Context get() = AppLocale.localized(this, container.cachedSettings)
    private val notification: TrackingNotification get() = TrackingNotification(localized)
    private var session: Session? = null
    private var locationJob: Job? = null
    private var lastNotificationUpdate = 0L
    private var filter = LocationFilter()
    private var jumps = JumpStreak(filter)

    override fun onCreate() {
        super.onCreate()
        container = (application as JustTrackerApplication).container
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
            jumps = JumpStreak(filter)
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
        s.pendingStart = null
        jumps.reset()
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
        jumps = JumpStreak(filter)
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
        if (s.stats.lastSample == null) {
            // First fix of a segment (start, resume, recovery): nothing to check it against, so it waits for the
            // next fix (docs/06_system_analysis.md §3.1 rule 6) instead of becoming the reference unchecked.
            val pending = s.pendingStart
            if (pending == null) {
                if (filter.evaluate(null, sample) is FilterResult.Accepted) s.pendingStart = PendingFix(sample, loc)
                return
            }
            when (filter.confirmStart(pending.sample, sample)) {
                StartCheck.IGNORE -> return
                StartCheck.REPLACE -> {
                    AppLog.geo { "segment start replaced: lat=${pending.sample.lat} lon=${pending.sample.lon} -> lat=${sample.lat} lon=${sample.lon}" }
                    s.pendingStart = PendingFix(sample, loc)
                    return
                }
                StartCheck.CONFIRMED -> {
                    s.pendingStart = null
                    if (!record(s, pending.sample, pending.location, distanceM = 0.0, speedMps = pending.sample.speedMps ?: 0f)) return
                }
            }
        }
        when (val result = filter.evaluate(s.stats.lastSample, sample)) {
            is FilterResult.Accepted -> {
                jumps.reset()
                record(s, sample, loc, result.distanceM, result.speedMps)
            }
            is FilterResult.Rejected -> {
                AppLog.geo { "rejected ${result.reason} acc=${sample.accuracyM} t=${sample.timestamp} lat=${sample.lat} lon=${sample.lon} prev=${s.stats.lastSample?.lat}" }
                when (result.reason) {
                    FilterResult.Reason.IMPLAUSIBLE_SPEED -> if (jumps.onImplausible(sample)) reanchor(s, sample, loc)
                    FilterResult.Reason.TOO_CLOSE -> jumps.reset()
                    FilterResult.Reason.INACCURATE, FilterResult.Reason.NOT_NEWER -> Unit
                }
            }
        }
    }

    /**
     * Consistent fixes keep arriving far from the last recorded one (LocationFilter rule 7): that one was the
     * outlier. The track continues from [sample] in a new segment, so no line joins the two places; a stray
     * start of a few points is deleted instead, so it neither shows on the map nor widens the fit.
     */
    private suspend fun reanchor(s: Session, sample: Sample, loc: Location) {
        AppLog.geo { "re-anchored at lat=${sample.lat} lon=${sample.lon}" }
        val deleted = container.trackRepository.deleteSegmentIfShort(s.track.id, s.segment, STRAY_SEGMENT_MAX_POINTS)
        if (deleted == 0) s.segment += 1
        s.stats.pointCount -= deleted
        s.stats.breakSegment()
        s.pendingStart = null
        record(s, sample, loc, distanceM = 0.0, speedMps = sample.speedMps ?: 0f)
    }

    /** Stores an accepted fix; false when the recording was finished because of it (storage full, point limit). */
    private suspend fun record(s: Session, sample: Sample, loc: Location, distanceM: Double, speedMps: Float): Boolean {
        s.stats.accept(sample, distanceM, speedMps)
        val point = TrackPoint(
            trackId = s.track.id,
            segment = s.segment,
            timestamp = sample.timestamp,
            lat = sample.lat,
            lon = sample.lon,
            altitudeM = if (loc.hasAltitude()) loc.altitude else null,
            accuracyM = sample.accuracyM,
            speedMps = speedMps,
            bearingDeg = if (loc.hasBearing()) loc.bearing else null,
            // Quality gate input for elevation gain/loss (docs/06_system_analysis.md §3.4).
            verticalAccuracyM = if (loc.hasAltitude() && loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters else null,
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
            return false
        }
        container.trackingController.update { it.copy(currentSpeedMps = s.stats.currentSpeedMps) }
        if (s.stats.pointCount >= MAX_POINTS_PER_TRACK) {
            AppLog.w("Max points reached, finishing track")
            handleStop()
            return false
        }
        refreshNotification(force = false)
        return true
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
        cachedFormatter ?: UnitFormatter(localized, container.cachedSettings.units).also { cachedFormatter = it }

    private fun autoName(type: ActivityType, startedAt: Long): String =
        localized.getString(type.labelRes()) + " · " + TimeFormat.dateShort(startedAt, AppLocale.current(container.cachedSettings))

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START = "com.justtracker.app.action.START"
        const val ACTION_PAUSE = "com.justtracker.app.action.PAUSE"
        const val ACTION_RESUME = "com.justtracker.app.action.RESUME"
        const val ACTION_STOP = "com.justtracker.app.action.STOP"
        const val ACTION_RECOVER = "com.justtracker.app.action.RECOVER"

        const val LOCATION_INTERVAL_MS = 1000L
        const val NOTIFICATION_THROTTLE_MS = 3000L
        const val MAX_POINTS_PER_TRACK = 100_000
        const val POSITION_MARKER_ACCURACY_M = 100f

        /** A segment this short that the track moved away from is a stray start, not a part of the route. */
        const val STRAY_SEGMENT_MAX_POINTS = 3
    }
}
