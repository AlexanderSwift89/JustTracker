package com.justtracker.app.data

import com.justtracker.app.data.repo.TrackRepository
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.TrackStatus
import com.justtracker.app.testing.FakeTrackDao
import com.justtracker.app.testing.walk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrackRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dao = FakeTrackDao()

    @Test
    fun `finishing computes the statistics, classifies and names the track`() = runTest {
        val repo = TrackRepository(dao)
        val track = repo.createTrack("draft", startedAt = 0)
        repo.walk(track, 120) // 3 m/s for two minutes
        val finished = repo.finish(track.id, finishedAt = 130_000) { type -> "auto $type" }!!
        assertEquals(TrackStatus.FINISHED, finished.status)
        assertEquals(357.0, finished.distanceM, 1.0)
        assertEquals(120, finished.pointCount)
        assertEquals(ActivityType.RUN, finished.activityType)
        assertEquals("auto RUN", finished.name)
        assertEquals(finished, repo.getTrack(track.id))
    }

    @Test
    fun `a type the user picked survives finishing`() = runTest {
        val repo = TrackRepository(dao)
        val track = repo.createTrack("mine", startedAt = 0)
        repo.walk(track, 30)
        repo.setActivityType(track.id, ActivityType.BIKE)
        val finished = repo.finish(track.id, finishedAt = 40_000) { "auto" }!!
        assertEquals(ActivityType.BIKE, finished.activityType)
        assertEquals("mine", finished.name)
    }

    @Test
    fun `a track with fewer than two points is discarded`() = runTest {
        val repo = TrackRepository(dao)
        val track = repo.createTrack("empty", startedAt = 0)
        repo.walk(track, 1)
        assertNull(repo.finish(track.id, finishedAt = 5_000) { "auto" })
        assertNull(repo.getTrack(track.id))
    }

    @Test
    fun `deleting a track takes its points and its shared GPX copies (SEC-11)`() = runTest {
        val exports = tmp.newFolder("exports")
        val repo = TrackRepository(dao, exports)
        val track = repo.createTrack("t", startedAt = 0)
        repo.walk(track, 5)
        val copy = File(exports, "Morning_walk_${track.id}.gpx").apply { writeText("<gpx/>") }
        val other = File(exports, "Other_${track.id + 1}.gpx").apply { writeText("<gpx/>") }
        repo.delete(track.id)
        assertNull(repo.getTrack(track.id))
        assertTrue(repo.getPoints(track.id).isEmpty())
        assertFalse(copy.exists())
        assertTrue(other.exists())
    }
}
