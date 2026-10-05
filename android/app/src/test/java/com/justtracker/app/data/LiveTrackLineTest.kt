package com.justtracker.app.data

import com.justtracker.app.data.repo.LiveTrackLine
import com.justtracker.app.data.repo.TrackRepository
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.track.TrackLine
import com.justtracker.app.testing.FakeTrackDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTrackLineTest {
    private val dao = FakeTrackDao()
    private val repo = TrackRepository(dao)
    private val live = LiveTrackLine(repo)

    private fun point(track: Track, i: Int, segment: Int = 0, timestamp: Long = 1_000L * i) = TrackPoint(
        trackId = track.id, segment = segment, timestamp = timestamp, lat = 55.0 + i * 1e-5, lon = 37.0,
        altitudeM = 150.0, accuracyM = 5f, speedMps = (i % 5).toFloat(), bearingDeg = null,
    )

    private suspend fun add(track: Track, p: TrackPoint) = repo.addPoint(p, track)

    /** The live line must always be what a full read of the stored points would give. */
    private suspend fun assertLine(track: Track, line: TrackLine) {
        val expected = TrackLine.of(repo.getPoints(track.id))
        assertEquals(expected.size, line.size)
        for (i in 0 until line.size) {
            assertEquals(expected.lat(i), line.lat(i), 0.0)
            assertEquals(expected.speedMps(i), line.speedMps(i), 0f)
            assertEquals(expected.distanceM(i), line.distanceM(i), 0.0)
        }
        assertEquals(expected.segmentCount, line.segmentCount)
    }

    @Test
    fun `a 1 Hz recording reads the whole track once, then only its tail`() = runTest {
        val track = repo.createTrack("t", 0L)
        repeat(3) { add(track, point(track, it)) }
        assertEquals(3, live.update(track).size)
        val readsAfterStart = dao.fullPointReads
        for (i in 3 until 200) {
            add(track, point(track, i))
            val line = live.update(track)
            assertEquals(i + 1, line.size)
        }
        assertLine(track, live.update(track))
        assertEquals(1, live.fullLoads)
        assertEquals(readsAfterStart + 1, dao.fullPointReads) // the one read of assertLine
    }

    @Test
    fun `a deleted stray start is noticed and the line read again`() = runTest {
        val track = repo.createTrack("t", 0L)
        repeat(10) { add(track, point(track, it)) }
        live.update(track)
        // segment 1 starts with two stray points that the recording then deletes (LocationFilter rule 7)
        add(track, point(track, 10, segment = 1))
        add(track, point(track, 11, segment = 1))
        live.update(track)
        repo.deleteSegmentIfShort(track.id, segment = 1, maxPoints = 3)
        add(track, point(track, 12, segment = 2))
        val line = live.update(track)
        assertEquals(2, live.fullLoads)
        assertLine(track, line)
        assertEquals(11, line.size)
    }

    @Test
    fun `deleted then replaced by as many points still reloads`() = runTest {
        val track = repo.createTrack("t", 0L)
        repeat(5) { add(track, point(track, it)) }
        live.update(track)
        add(track, point(track, 5, segment = 1))
        live.update(track)
        repo.deleteSegmentIfShort(track.id, segment = 1, maxPoints = 3) // −1
        add(track, point(track, 6, segment = 2)) // +1: same count as before
        assertLine(track, live.update(track))
        assertEquals(2, live.fullLoads)
    }

    @Test
    fun `a point older than the known ones forces a full read`() = runTest {
        val track = repo.createTrack("t", 0L)
        repeat(5) { add(track, point(track, it)) }
        live.update(track)
        add(track, point(track, 5, timestamp = 2_500L))
        assertLine(track, live.update(track))
        assertEquals(2, live.fullLoads)
    }

    @Test
    fun `another track starts afresh and no track gives an empty line`() = runTest {
        val first = repo.createTrack("a", 0L)
        repeat(5) { add(first, point(first, it)) }
        live.update(first)
        val second = repo.createTrack("b", 10_000L)
        repeat(3) { add(second, point(second, it)) }
        assertLine(second, live.update(second))
        assertTrue(live.update(null).isEmpty)
        assertEquals(2, live.fullLoads)
    }

    @Test
    fun `nothing new keeps the very same snapshot`() = runTest {
        val track = repo.createTrack("t", 0L)
        repeat(5) { add(track, point(track, it)) }
        val a = live.update(track)
        val b = live.update(track)
        assertTrue(a === b)
    }
}
