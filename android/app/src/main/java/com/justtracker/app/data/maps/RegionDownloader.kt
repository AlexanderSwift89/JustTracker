package com.justtracker.app.data.maps

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.justtracker.app.JustTrackerApplication
import com.justtracker.app.domain.maps.RegionError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** Snapshot of one system download. */
data class DownloadStatus(val state: Int, val bytes: Long, val total: Long, val reason: Int) {
    val progress: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    val running: Boolean get() = state == DownloadManager.STATUS_RUNNING
    val paused: Boolean get() = state == DownloadManager.STATUS_PAUSED || state == DownloadManager.STATUS_PENDING
    val successful: Boolean get() = state == DownloadManager.STATUS_SUCCESSFUL
    val failed: Boolean get() = state == DownloadManager.STATUS_FAILED
    /** Paused because the allowed network (Wi-Fi) is not available. */
    val waitingForNetwork: Boolean
        get() = state == DownloadManager.STATUS_PAUSED &&
            (reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK || reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI)
}

/**
 * Thin wrapper over the system [DownloadManager]: it survives process death, resumes, shows its own
 * notification and only writes to app-specific external storage (see [MapsDirectory]).
 */
class RegionDownloader(private val context: Context) {
    private val manager: DownloadManager? = context.getSystemService(DownloadManager::class.java)

    /** Enqueues [url] into `<external files>/maps/<fileName>`; returns the download id, or null when unavailable. */
    fun enqueue(url: String, fileName: String, title: String, wifiOnly: Boolean): Long? {
        val dm = manager ?: return null
        val request = DownloadManager.Request(url.toUri())
            .setTitle(title)
            .setDestinationInExternalFilesDir(context, MapsDirectory.DIR_NAME, fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverRoaming(false)
            .setAllowedOverMetered(!wifiOnly)
        if (wifiOnly) request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
        return runCatching { dm.enqueue(request) }.getOrNull()
    }

    fun status(downloadId: Long): DownloadStatus? {
        val dm = manager ?: return null
        val cursor = runCatching { dm.query(DownloadManager.Query().setFilterById(downloadId)) }.getOrNull() ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return DownloadStatus(
                state = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                bytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            )
        }
    }

    /** Polls [status] while collected; completes when the download is no longer known. */
    fun progress(downloadId: Long, periodMs: Long = 1_000L): Flow<DownloadStatus> = flow {
        while (true) {
            val s = status(downloadId) ?: break
            emit(s)
            if (s.successful || s.failed) break
            delay(periodMs)
        }
    }.flowOn(Dispatchers.IO)

    /** Cancels and removes the partial file. */
    fun cancel(downloadId: Long) {
        runCatching { manager?.remove(downloadId) }
    }

    companion object {
        /** Maps DownloadManager failure reasons to what the user can act on. */
        fun errorFor(reason: Int): RegionError = when (reason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> RegionError.NO_SPACE
            DownloadManager.ERROR_HTTP_DATA_ERROR,
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
            DownloadManager.ERROR_TOO_MANY_REDIRECTS,
            DownloadManager.ERROR_CANNOT_RESUME,
            -> RegionError.NETWORK
            DownloadManager.ERROR_FILE_ERROR,
            DownloadManager.ERROR_DEVICE_NOT_FOUND,
            DownloadManager.ERROR_FILE_ALREADY_EXISTS,
            -> RegionError.CORRUPT
            else -> if (reason in 400..599) RegionError.NETWORK else RegionError.UNKNOWN
        }
    }
}

/**
 * Manifest-registered so a download that finishes while the app is dead still gets verified.
 * The id is only ever looked up in DownloadManager (which returns this app's downloads only), so a
 * spoofed broadcast cannot do harm.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0) return
        val container = (context.applicationContext as? JustTrackerApplication)?.container ?: return
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.offlineRegionStore.onDownloadFinished(id)
            } finally {
                pending.finish()
            }
        }
    }
}
