package com.justtracker.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justtracker.app.data.db.JustTrackerDatabase
import com.justtracker.app.data.db.TrackDao
import com.justtracker.app.data.db.TrackEntity
import com.justtracker.app.data.db.TrackPointEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The SQL behind the live line's tail loading and the recording's stray-start deletion, on a real SQLite (ADR-25). */
@RunWith(AndroidJUnit4::class)
class TrackDaoTest {
    private lateinit var db: JustTrackerDatabase
    private lateinit var dao: TrackDao

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), JustTrackerDatabase::class.java).build()
        dao = db.trackDao()
    }

    @After
    fun close() = db.close()

    private fun track() = TrackEntity(
        name = "t", status = "RECORDING", activityType = "UNKNOWN", activityManual = false, startedAt = 0, finishedAt = null,
        distanceM = 0.0, movingTimeMs = 0, totalTimeMs = 0, pausedTimeMs = 0, avgSpeedMps = 0.0, maxSpeedMps = 0.0,
        elevationGainM = 0.0, elevationLossM = 0.0, pointCount = 0,
    )

    private fun point(trackId: Long, i: Int, segment: Int = 0) = TrackPointEntity(
        trackId = trackId, segment = segment, timestamp = i * 1000L, lat = 55.0 + i * 1e-5, lon = 37.0,
        altitudeM = null, accuracyM = 5f, speedMps = 1f, bearingDeg = null,
    )

    @Test
    fun tailReturnsOnlyNewerPointsInIdOrder() = runBlocking {
        val id = dao.insertTrack(track())
        val ids = (0 until 5).map { dao.insertPoint(point(id, it)) }
        assertEquals(ids.drop(3), dao.getPointsAfter(id, ids[2]).map { it.id })
        assertEquals(ids, dao.pointsAfterIfIntact(id, 0)!!.map { it.id })
        assertTrue(dao.getPointsAfter(id, ids.last()).isEmpty())
    }

    @Test
    fun aDeletedLastKnownPointMakesTheTailNull() = runBlocking {
        val id = dao.insertTrack(track())
        repeat(3) { dao.insertPoint(point(id, it)) }
        val stray = (3 until 5).map { dao.insertPoint(point(id, it, segment = 1)) }
        assertEquals(2, dao.countSegmentPoints(id, 1))
        dao.deleteSegmentPoints(id, 1)
        assertFalse(dao.pointExists(stray.last()))
        assertNull(dao.pointsAfterIfIntact(id, stray.last()))
        assertEquals(3, dao.getPoints(id).size)
    }

    @Test
    fun deletingATrackCascadesToItsPoints() = runBlocking {
        val id = dao.insertTrack(track())
        val p = dao.insertPoint(point(id, 0))
        dao.deleteTrack(id)
        assertNull(dao.getTrack(id))
        assertFalse(dao.pointExists(p))
    }
}
