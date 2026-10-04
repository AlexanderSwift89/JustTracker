package com.justtracker.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.justtracker.app.domain.stats.Acceleration
import com.justtracker.app.domain.stats.AccelerationTrace
import com.justtracker.app.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Ephemeral, in-memory view of the recording service that the DB cannot provide: instantaneous
 * speed and acceleration, GPS freshness and whether the service process is alive. Persistent state
 * (track, points, totals) lives in Room and is observed directly by the UI.
 */
data class LiveTrackingState(
    val serviceRunning: Boolean = false,
    val currentSpeedMps: Float = 0f,
    /** Along-track horizontal acceleration (ADR-20); null while there is no estimate. */
    val acceleration: Acceleration? = null,
    /** Wall-clock time a fix last confirmed [acceleration], so the UI can tell a stale value like with [lastFixAt]. */
    val accelerationAt: Long = 0L,
    /** Acceleration over the last minute, one value per second. */
    val accelerationTrace: AccelerationTrace = AccelerationTrace.EMPTY,
    val lastFixAt: Long = 0L,
    val lastLat: Double? = null,
    val lastLon: Double? = null,
    val permissionLost: Boolean = false,
    /** Incremented on every finish so the UI can react once per event. */
    val finishSerial: Int = 0,
    val lastFinishedTrackId: Long? = null,
    /** True when the last finish discarded the track for having too few points. */
    val lastFinishDiscarded: Boolean = false,
)

/** Single entry point the UI uses to drive [TrackingService]. */
class TrackingController(private val context: Context) {
    private val _live = MutableStateFlow(LiveTrackingState())
    val live: StateFlow<LiveTrackingState> = _live

    fun start() = send(TrackingService.ACTION_START, foreground = true)
    fun pause() = send(TrackingService.ACTION_PAUSE)
    fun resume() = send(TrackingService.ACTION_RESUME, foreground = true)
    fun stop() = send(TrackingService.ACTION_STOP)

    /** Re-attach to a track left in RECORDING/PAUSED state after process death. */
    fun recover() = send(TrackingService.ACTION_RECOVER, foreground = true)

    internal fun update(block: (LiveTrackingState) -> LiveTrackingState) = _live.update(block)

    private fun send(action: String, foreground: Boolean = false) {
        val intent = Intent(context, TrackingService::class.java).setAction(action)
        AppLog.d("TrackingController: $action")
        if (foreground) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            // Service is already in foreground; a plain startService is enough to deliver the action.
            context.startService(intent)
        }
    }
}
