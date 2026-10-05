package com.justtracker.app.data.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.justtracker.app.data.repo.TrackRepository
import com.justtracker.app.di.AppDispatchers
import com.justtracker.app.domain.gpx.GpxWriter
import com.justtracker.app.util.AppLog
import kotlinx.coroutines.withContext
import java.io.File

/** Prepares a track for the system share sheet as a GPX file (docs/06_system_analysis.md UC-04). */
fun interface GpxExporter {
    /** A share intent for the track, or null when it does not exist or the file could not be written. */
    suspend fun shareIntent(trackId: Long): Intent?
}

/**
 * Writes the GPX into cacheDir/exports and shares it through the app's FileProvider. Files older than 24 h are purged
 * on every export and on app start; a deleted track takes its copies with it ([ExportFiles], SEC-11). The read grant
 * covers exactly this URI via ClipData (SEC-17).
 */
class FileProviderGpxExporter(
    private val context: Context,
    private val tracks: TrackRepository,
    private val dispatchers: AppDispatchers,
) : GpxExporter {
    override suspend fun shareIntent(trackId: Long): Intent? = withContext(dispatchers.io) {
        val track = tracks.getTrack(trackId) ?: return@withContext null
        val points = tracks.getPoints(trackId)
        try {
            val dir = ExportFiles.dir(context.cacheDir).apply { mkdirs() }
            ExportFiles.purgeOlderThan(dir, System.currentTimeMillis())
            // A renamed track would otherwise leave its previous copy behind.
            ExportFiles.deleteForTrack(dir, trackId)
            val file = File(dir, GpxWriter.fileName(track))
            file.bufferedWriter().use { GpxWriter.write(track, points, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, track.name)
                clipData = ClipData.newRawUri(track.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            AppLog.e("GPX export failed", e)
            null
        }
    }
}
