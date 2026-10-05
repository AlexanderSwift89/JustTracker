package com.justtracker.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.justtracker.app.R
import com.justtracker.app.util.UnitFormatter

/**
 * Builds the persistent foreground notification for [TrackingService] in the resources of [context] (the app
 * language). One instance lives as long as the language and units do: the builder and the pending intents are made
 * once and only the texts change on each update (every few seconds while recording).
 */
class TrackingNotification(private val context: Context) {

    /** Creates the channel, or renames it after a language change (name and description may be updated). */
    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // The launcher intent of the own package opens the app like its icon does (MainActivity is singleTask), without
    // the service layer depending on the UI class.
    private val openIntent: PendingIntent by lazy {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        PendingIntent.getActivity(
            context,
            0,
            launch.setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
    private val resumeIntent by lazy { servicePending(TrackingService.ACTION_RESUME, 1) }
    private val pauseIntent by lazy { servicePending(TrackingService.ACTION_PAUSE, 2) }
    private val stopIntent by lazy { servicePending(TrackingService.ACTION_STOP, 3) }

    private val builder: NotificationCompat.Builder by lazy {
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }

    /**
     * @param startedAt track start; shown as a live chronometer (recording time, pauses included)
     *   so the time keeps ticking between location updates.
     */
    fun build(
        paused: Boolean,
        startedAt: Long,
        distanceM: Double,
        speedMps: Double,
        formatter: UnitFormatter,
    ): Notification {
        val text = context.getString(
            R.string.notification_text_format,
            formatter.distance(distanceM),
            formatter.speed(speedMps),
        )
        builder
            .setContentTitle(context.getString(if (paused) R.string.notification_title_paused else R.string.notification_title_recording))
            .setContentText(text)
            .setWhen(startedAt)
            .clearActions()
        if (paused) {
            builder.addAction(0, context.getString(R.string.record_resume), resumeIntent)
        } else {
            builder.addAction(0, context.getString(R.string.record_pause), pauseIntent)
        }
        builder.addAction(0, context.getString(R.string.record_stop), stopIntent)
        return builder.build()
    }

    private fun servicePending(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, TrackingService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_ID = "tracking"
        const val NOTIFICATION_ID = 1001
    }
}
