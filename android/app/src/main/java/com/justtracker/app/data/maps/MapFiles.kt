package com.justtracker.app.data.maps

import android.content.Context
import android.os.StatFs
import com.justtracker.app.domain.maps.LatLonBox
import com.justtracker.app.util.AppLog
import org.mapsforge.map.reader.MapFile
import java.io.File

/**
 * Where region files live. `DownloadManager` can only write to external storage, so the app-specific
 * external files dir is used (no permission needed, private to the app, removed on uninstall). When
 * the device has no external storage the internal dir is used and only Import is available.
 */
object MapsDirectory {
    const val DIR_NAME = "maps"

    fun resolve(context: Context): File =
        (context.getExternalFilesDir(DIR_NAME) ?: File(context.filesDir, DIR_NAME)).also { it.mkdirs() }

    /** True when [resolve] points at external storage, i.e. DownloadManager downloads are possible. */
    fun supportsDownloads(context: Context): Boolean = context.getExternalFilesDir(DIR_NAME) != null

    fun freeBytes(dir: File): Long = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)

    fun usedBytes(dir: File): Long =
        dir.listFiles { f -> f.isFile && f.name.endsWith(MAP_SUFFIX) }?.sumOf { it.length() } ?: 0L

    const val MAP_SUFFIX = ".map"
    const val PART_SUFFIX = ".map.part"
}

/** Header facts of a Mapsforge file; null bbox means the file is not a readable map. */
data class MapFileInfo(val box: LatLonBox, val sizeBytes: Long, val languages: String?)

/** Reads only the header of a `.map` file — cheap even for multi-GB files. */
object MapFileInspector {
    fun inspect(file: File): MapFileInfo? {
        if (!file.isFile || file.length() == 0L) return null
        var map: MapFile? = null
        return try {
            map = MapFile(file)
            val info = map.mapFileInfo
            val bb = info.boundingBox
            MapFileInfo(
                box = LatLonBox(bb.minLatitude, bb.minLongitude, bb.maxLatitude, bb.maxLongitude),
                sizeBytes = file.length(),
                languages = info.languagesPreference,
            )
        } catch (e: Exception) {
            AppLog.w("Not a Mapsforge map: ${file.name}", e)
            null
        } finally {
            runCatching { map?.close() }
        }
    }
}
