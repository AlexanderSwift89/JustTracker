package com.justtracker.app.domain

import com.justtracker.app.domain.geo.Geo
import com.justtracker.app.domain.geo.Sample
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.recording.Fix
import com.justtracker.app.domain.recording.RecorderStore
import com.justtracker.app.domain.recording.StopReason
import com.justtracker.app.domain.recording.TrackRecorder
import com.justtracker.app.domain.recording.TrackTotals
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the recording rules that moved out of TrackingService (ADR-27); expectations follow LocationFilter rules 1–7. */
class TrackRecorderTest {
    private class FakeStore(private val capacity: Int = Int.MAX_VALUE) : RecorderStore {
        val points = mutableListOf<TrackPoint>()
        var lastTotals: TrackTotals? = null

        override suspend fun insert(point: TrackPoint, totals: TrackTotals): Boolean {
            if (points.size >= capacity) return false
            points += point
            lastTotals = totals
            return true
        }

        override suspend fun deleteSegmentIfShort(trackId: Long, segment: Int, maxPoints: Int): Int {
            val n = points.count { it.segment == segment }
            if (n == 0 || n > maxPoints) return 0
            points.removeAll { it.segment == segment }
            return n
        }
    }

    private val metersPerDegree = Geo.METERS_PER_DEGREE

    /** A fix [second] seconds in, [northM] metres north of 55° N 37° E. */
    private fun fix(second: Int, northM: Double, accuracy: Float = 5f, speed: Float? = null, speedAccuracy: Float? = null, altitude: Double? = null) = Fix(
        sample = Sample(timestamp = second * 1000L, lat = 55.0 + northM / metersPerDegree, lon = 37.0, accuracyM = accuracy, speedMps = speed),
        monotonicMs = second * 1000L,
        speedAccuracyMps = speedAccuracy,
        altitudeM = altitude,
    )

    private fun recorder(store: FakeStore, segment: Int = 0, totals: TrackTotals = TrackTotals(), maxPoints: Int = 100_000) =
        TrackRecorder(trackId = 7, startSegment = segment, totals = totals, maxAccuracyM = 50f, store = store, maxPoints = maxPoints)

    @Test
    fun `the first fix waits for the next one, then both are stored`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        assertEquals(0, r.onFix(fix(0, 0.0)).stored)
        assertEquals(2, r.onFix(fix(1, 3.0)).stored)
        for (s in 2..20) assertEquals(1, r.onFix(fix(s, 3.0 * s)).stored)
        assertEquals(21, store.points.size)
        assertEquals(60.0, r.totals.distanceM, 0.5)
        assertEquals(21, store.lastTotals!!.pointCount)
        assertTrue(store.points.all { it.trackId == 7L && it.segment == 0 })
    }

    @Test
    fun `standing still stores a point every 30 s`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        for (s in 0..90) r.onFix(fix(s, 0.0, speed = 0f))
        assertEquals(listOf(0L, 30_000L, 60_000L, 90_000L), store.points.map { it.timestamp })
        assertEquals(0.0, r.totals.distanceM, 1e-9)
    }

    @Test
    fun `a far first fix is replaced by the real position (D-19)`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        r.onFix(fix(0, 10_000.0)) // stale position 10 km away
        for (s in 1..10) r.onFix(fix(s, 3.0 * s))
        assertTrue(store.points.isNotEmpty())
        assertTrue(store.points.all { (it.lat - 55.0) * metersPerDegree < 100 })
        assertEquals(27.0, r.totals.distanceM, 0.5)
    }

    @Test
    fun `inaccurate and repeated fixes are not stored`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        r.onFix(fix(0, 0.0))
        r.onFix(fix(1, 3.0))
        assertEquals(0, r.onFix(fix(2, 6.0, accuracy = 80f)).stored)
        assertEquals(0, r.onFix(fix(1, 9.0)).stored) // not newer
        assertEquals(2, store.points.size)
    }

    @Test
    fun `only accurate new fixes keep the GPS fresh (D-39)`() = runTest {
        val r = recorder(FakeStore())
        assertTrue(r.onFix(fix(0, 0.0)).usable)
        assertTrue(r.onFix(fix(1, 3.0)).usable)
        // Underground: cell positions far too coarse to store, and the same fix delivered again.
        assertFalse(r.onFix(fix(2, 900.0, accuracy = 900f)).usable)
        assertFalse(r.onFix(fix(1, 3.0)).usable)
        // A fix too close to store is still the receiver delivering positions.
        assertTrue(r.onFix(fix(3, 3.5)).usable)
    }

    @Test
    fun `the marker follows fixes up to 100 m accuracy`() = runTest {
        val r = recorder(FakeStore())
        assertNull(r.onFix(fix(0, 0.0, accuracy = 150f)).marker)
        val coarse = r.onFix(fix(1, 0.0, accuracy = 80f)) // too coarse to store, fine for the marker
        assertNotNull(coarse.marker)
        assertEquals(0, coarse.stored)
    }

    @Test
    fun `a stray start of two points is deleted when consistent fixes go on elsewhere (rule 7)`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        r.onFix(fix(0, 5_000.0))
        r.onFix(fix(1, 5_003.0))
        assertEquals(2, store.points.size)
        var last = 0
        for (s in 2..6) last = r.onFix(fix(s, 3.0 * s)).stored
        assertEquals(1, last)
        assertEquals(1, store.points.size)
        assertEquals(0, store.points.single().segment)
        assertEquals(1, r.totals.pointCount)
    }

    @Test
    fun `after a real segment the jump starts a new segment`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        for (s in 0..9) r.onFix(fix(s, 5_000.0 + 3.0 * s))
        assertEquals(10, store.points.size)
        for (s in 10..14) r.onFix(fix(s, 3.0 * s))
        assertEquals(11, store.points.size)
        assertEquals(1, store.points.last().segment)
        assertEquals(1, r.segment)
    }

    @Test
    fun `pause and resume continue in the next segment`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        for (s in 0..4) r.onFix(fix(s, 3.0 * s))
        r.pause()
        r.resume()
        assertEquals(0, r.onFix(fix(100, 300.0)).stored) // the first fix of the segment waits again
        assertEquals(2, r.onFix(fix(101, 303.0)).stored)
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 1), store.points.map { it.segment })
        assertEquals(12.0 + 3.0, r.totals.distanceM, 0.5) // the gap is not counted
    }

    @Test
    fun `the point limit stops the recording`() = runTest {
        val store = FakeStore()
        val r = recorder(store, maxPoints = 5)
        r.onFix(fix(0, 0.0))
        r.onFix(fix(1, 3.0))
        r.onFix(fix(2, 6.0))
        r.onFix(fix(3, 9.0))
        val outcome = r.onFix(fix(4, 12.0))
        assertEquals(StopReason.POINT_LIMIT, outcome.stop)
        assertEquals(5, store.points.size)
    }

    @Test
    fun `a full storage stops the recording`() = runTest {
        val store = FakeStore(capacity = 3)
        val r = recorder(store)
        r.onFix(fix(0, 0.0))
        r.onFix(fix(1, 3.0))
        assertNull(r.onFix(fix(2, 6.0)).stop)
        assertEquals(StopReason.STORAGE_FULL, r.onFix(fix(3, 9.0)).stop)
    }

    @Test
    fun `recovery continues the totals in the given segment`() = runTest {
        val store = FakeStore()
        val r = recorder(store, segment = 3, totals = TrackTotals(distanceM = 1000.0, movingTimeMs = 600_000, maxSpeedMps = 4.0, pointCount = 50))
        r.onFix(fix(0, 0.0))
        r.onFix(fix(1, 3.0))
        assertEquals(52, r.totals.pointCount)
        assertEquals(1003.0, r.totals.distanceM, 0.5)
        assertTrue(store.points.all { it.segment == 3 })
    }

    @Test
    fun `live speed comes from every fix with a trustworthy Doppler speed, else from stored points`() = runTest {
        val r = recorder(FakeStore())
        r.onFix(fix(0, 0.0, speed = 1.2f, speedAccuracy = 0.2f))
        val close = r.onFix(fix(1, 1.2, speed = 1.2f, speedAccuracy = 0.2f)) // too close to store as a new point
        assertNotNull(close.speedMps)
        // Without a Doppler speed a fix that stores nothing leaves the live speed as it was.
        val noDoppler = recorder(FakeStore())
        noDoppler.onFix(fix(0, 0.0))
        assertNotNull(noDoppler.onFix(fix(1, 3.0)).speedMps) // stored: speed of the stored points
        assertNull(noDoppler.onFix(fix(2, 3.5)).speedMps) // too close, not stored, no Doppler
    }

    @Test
    fun `altitude and its accuracy are stored with the point`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        r.onFix(fix(0, 0.0, altitude = 150.0))
        r.onFix(fix(1, 3.0, altitude = 151.0))
        assertEquals(listOf(150.0, 151.0), store.points.map { it.altitudeM })
    }

    @Test
    fun `the speed accuracy is stored only with the receiver's own speed`() = runTest {
        val store = FakeStore()
        val r = recorder(store)
        r.onFix(fix(0, 0.0, speed = 3f, speedAccuracy = 0.2f))
        r.onFix(fix(1, 3.0, speed = 3f, speedAccuracy = 0.3f))
        // The receiver claims a standstill while the position moved 3 m: the stored speed is the displacement's.
        r.onFix(fix(2, 6.0, speed = 0f, speedAccuracy = 0.4f))
        // No accuracy reported, no speed reported.
        r.onFix(fix(3, 9.0, speed = 3f))
        r.onFix(fix(4, 12.0, speedAccuracy = 0.5f))
        assertEquals(listOf(0.2f, 0.3f, null, null, null), store.points.map { it.speedAccuracyMps })
        assertEquals(3f, store.points[2].speedMps!!, 1e-3f)
    }
}
