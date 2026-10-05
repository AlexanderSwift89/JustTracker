package com.justtracker.app.data.maps

import android.content.Context
import android.os.StatFs
import com.justtracker.app.domain.maps.LatLonBox
import com.justtracker.app.util.AppLog
import org.mapsforge.map.reader.MapFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Where region files live. `DownloadManager` can only write to external storage, so the app-specific
 * external files dir is used (no permission needed, removed on uninstall). On Android 10 and older, apps
 * holding the storage permission can read and write it too — accepted for public map data: files are only
 * parsed by Mapsforge's Java reader, a download is checked against the catalogue before READY, and every
 * region is listed in Offline maps (docs/07_security.md SEC-13). When the device has no external storage the
 * internal dir is used and only Import is available.
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
            notAMap(file, e)
        } catch (e: OutOfMemoryError) {
            // A forged header can ask for a huge buffer or nest deeply: the file is rejected, the app keeps running (SEC-18).
            notAMap(file, e)
        } catch (e: StackOverflowError) {
            notAMap(file, e)
        } finally {
            runCatching { map?.close() }
        }
    }

    private fun notAMap(file: File, e: Throwable): MapFileInfo? {
        AppLog.d("Not a Mapsforge map: ${file.name}")
        AppLog.w("Not a Mapsforge map", e)
        return null
    }
}

/** The picked file is bigger than the space the import may use. */
class ImportTooLargeException : IOException("Import exceeds the free-space budget")

/**
 * Copies [input] to [output] but never more than [limit] bytes: a document provider may stream without end or
 * report a wrong size, and a full disk would end a running recording (SEC-16). Returns the bytes copied.
 */
fun copyAtMost(input: InputStream, output: OutputStream, limit: Long, bufferSize: Int = 64 * 1024): Long {
    val buffer = ByteArray(bufferSize)
    var copied = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return copied
        if (copied + n > limit) throw ImportTooLargeException()
        output.write(buffer, 0, n)
        copied += n
    }
}
