package com.justtracker.app.data

import com.justtracker.app.data.export.ExportFiles
import com.justtracker.app.domain.gpx.GpxWriter
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.TrackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExportFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_760_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun file(dir: File, name: String, ageMs: Long = 0L) =
        File(dir, name).apply { writeText("<gpx/>"); setLastModified(now - ageMs) }

    @Test
    fun `purge removes only files older than max age`() {
        val dir = ExportFiles.dir(tmp.root).apply { mkdirs() }
        val old = file(dir, "Run_1.gpx", ageMs = 25 * hour)
        val fresh = file(dir, "Walk_2.gpx", ageMs = 23 * hour)
        assertEquals(1, ExportFiles.purgeOlderThan(dir, now))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `deleteForTrack removes only that track's files`() {
        val dir = tmp.newFolder(ExportFiles.DIR_NAME)
        val mine = listOf(file(dir, "Run_1.gpx"), file(dir, "Renamed_run_1.gpx"))
        val others = listOf(file(dir, "Walk_11.gpx"), file(dir, "Ride_21.gpx"), file(dir, "track_1.gpx.tmp"))
        assertEquals(2, ExportFiles.deleteForTrack(dir, 1))
        assertTrue(mine.none { it.exists() })
        assertTrue(others.all { it.exists() })
    }

    @Test
    fun `deleteForTrack matches GpxWriter file names`() {
        val track = Track(
            id = 42,
            name = "Прогулка · 5 окт., 08:15",
            status = TrackStatus.FINISHED,
            activityType = ActivityType.WALK,
            activityManual = false,
            startedAt = now,
            finishedAt = now + hour,
        )
        val dir = tmp.newFolder(ExportFiles.DIR_NAME)
        val exported = file(dir, GpxWriter.fileName(track))
        assertTrue(ExportFiles.belongsTo(exported.name, 42))
        assertFalse(ExportFiles.belongsTo(exported.name, 2))
        assertEquals(1, ExportFiles.deleteForTrack(dir, 42))
        assertFalse(exported.exists())
    }

    @Test
    fun `missing directory is not an error`() {
        val missing = File(tmp.root, "nope")
        assertEquals(0, ExportFiles.purgeOlderThan(missing, now))
        assertEquals(0, ExportFiles.deleteForTrack(missing, 1))
    }
}
