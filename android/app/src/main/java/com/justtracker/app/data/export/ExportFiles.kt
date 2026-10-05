package com.justtracker.app.data.export

import java.io.File

/**
 * GPX copies made for "Share" live in `cache/exports/` — the only folder the FileProvider exposes
 * (`res/xml/file_paths.xml`). A copy is a full-precision track outside the database, so it is removed together
 * with its track and at the latest [MAX_AGE_MS] after export (docs/06_system_analysis.md UC-04, SEC-11).
 */
object ExportFiles {
    /** Must match `<cache-path name="exports" path="exports/"/>`. */
    const val DIR_NAME = "exports"

    /** The receiving app may read the file lazily (e.g. a messenger uploading in the background). */
    const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    fun dir(cacheDir: File): File = File(cacheDir, DIR_NAME)

    /** Deletes files modified more than [maxAgeMs] before [nowMs]; returns how many. A missing folder is fine. */
    fun purgeOlderThan(dir: File, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): Int =
        dir.listFiles()?.count { it.isFile && it.lastModified() < nowMs - maxAgeMs && it.delete() } ?: 0

    /** Deletes every copy of the track — also ones exported under an earlier name; returns how many. */
    fun deleteForTrack(dir: File, trackId: Long): Int =
        dir.listFiles()?.count { it.isFile && belongsTo(it.name, trackId) && it.delete() } ?: 0

    /** `GpxWriter.fileName` always ends with `_<id>.gpx` (the sanitised name never ends with `_`). */
    internal fun belongsTo(fileName: String, trackId: Long): Boolean = fileName.endsWith("_$trackId.gpx")
}
