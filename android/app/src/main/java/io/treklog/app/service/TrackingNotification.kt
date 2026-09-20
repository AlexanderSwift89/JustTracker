package io.treklog.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.treklog.app.R
import io.treklog.app.ui.MainActivity
import io.treklog.app.util.UnitFormatter

/** Builds the persistent foreground notification for [TrackingService]. */
class TrackingNotification(private val context: Context) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
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
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.getString(
            R.string.notification_text_format,
            formatter.distance(distanceM),
            formatter.speed(speedMps),
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(if (paused) R.string.notification_title_paused else R.string.notification_title_recording))
            .setContentText(text)
            .setWhen(startedAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (paused) {
            builder.addAction(0, context.getString(R.string.record_resume), servicePending(TrackingService.ACTION_RESUME, 1))
        } else {
            builder.addAction(0, context.getString(R.string.record_pause), servicePending(TrackingService.ACTION_PAUSE, 2))
        }
        builder.addAction(0, context.getString(R.string.record_stop), servicePending(TrackingService.ACTION_STOP, 3))
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
