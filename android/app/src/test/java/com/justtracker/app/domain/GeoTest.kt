package com.justtracker.app.domain

import com.justtracker.app.domain.geo.FilterResult
import com.justtracker.app.domain.geo.Geo
import com.justtracker.app.domain.geo.JumpStreak
import com.justtracker.app.domain.geo.LocationFilter
import com.justtracker.app.domain.geo.Sample
import com.justtracker.app.domain.geo.StartCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test
    fun `haversine matches known distance Moscow to Saint Petersburg`() {
        val d = Geo.distanceMeters(55.7558, 37.6173, 59.9343, 30.3351)
        assertEquals(634_000.0, d, 3_000.0)
    }

    @Test
    fun `haversine zero for identical points`() {
        assertEquals(0.0, Geo.distanceMeters(10.0, 20.0, 10.0, 20.0), 1e-9)
    }

    @Test
    fun `haversine one degree latitude is about 111 km`() {
        assertEquals(111_195.0, Geo.distanceMeters(0.0, 0.0, 1.0, 0.0), 50.0)
    }
}

class LocationFilterTest {
    private val filter = LocationFilter(maxAccuracyM = 50f)
    private fun s(t: Long, lat: Double, lon: Double, acc: Float = 10f, speed: Float? = null) = Sample(t, lat, lon, acc, speed)

    @Test
    fun `first sample accepted when accurate`() {
        val r = filter.evaluate(null, s(0, 55.0, 37.0))
        assertTrue(r is FilterResult.Accepted)
    }

    @Test
    fun `inaccurate sample rejected`() {
        val r = filter.evaluate(null, s(0, 55.0, 37.0, acc = 80f))
        assertEquals(FilterResult.Rejected(FilterResult.Reason.INACCURATE), r)
    }

    @Test
    fun `older timestamp rejected`() {
        val prev = s(1000, 55.0, 37.0)
        val r = filter.evaluate(prev, s(1000, 55.0001, 37.0))
        assertEquals(FilterResult.Rejected(FilterResult.Reason.NOT_NEWER), r)
    }

    @Test
    fun `tiny movement rejected as too close`() {
        val prev = s(0, 55.0, 37.0)
        val r = filter.evaluate(prev, s(1000, 55.000005, 37.0)) // ~0.5 m
        assertEquals(FilterResult.Rejected(FilterResult.Reason.TOO_CLOSE), r)
    }

    @Test
    fun `stationary point accepted after 30 seconds`() {
        val prev = s(0, 55.0, 37.0)
        val r = filter.evaluate(prev, s(30_000, 55.000005, 37.0))
        assertTrue(r is FilterResult.Accepted)
    }

    @Test
    fun `implausible jump rejected`() {
        val prev = s(0, 55.0, 37.0)
        val r = filter.evaluate(prev, s(1000, 55.01, 37.0)) // ~1.1 km in 1 s
        assertEquals(FilterResult.Rejected(FilterResult.Reason.IMPLAUSIBLE_SPEED), r)
    }

    @Test
    fun `speed derived from distance when GPS speed missing`() {
        val prev = s(0, 55.0, 37.0)
        val r = filter.evaluate(prev, s(10_000, 55.0001, 37.0)) as FilterResult.Accepted // ~11.1 m in 10 s
        assertEquals(1.11f, r.speedMps, 0.05f)
        assertEquals(11.1, r.distanceM, 0.2)
    }

    @Test
    fun `reported GPS speed preferred over derived`() {
        val prev = s(0, 55.0, 37.0)
        val r = filter.evaluate(prev, s(10_000, 55.0001, 37.0, speed = 2.5f)) as FilterResult.Accepted
        assertEquals(2.5f, r.speedMps, 0f)
    }

    @Test
    fun `far-off first fix is replaced instead of blocking the recording`() {
        // The emulator case of the 1.0.2 rotation tests: the first fix came from the previous position (Malta),
        // every real fix from Moscow was then an "implausible jump" and the track stayed at 0 m.
        val staleFirst = s(0, 35.8989, 14.5146)
        assertEquals(StartCheck.REPLACE, filter.confirmStart(staleFirst, s(1_000, 55.8515, 37.4700)))
    }

    @Test
    fun `consistent next fix confirms the first one`() {
        val first = s(0, 55.0, 37.0)
        assertEquals(StartCheck.CONFIRMED, filter.confirmStart(first, s(1_000, 55.00003, 37.0))) // moved 3 m
        assertEquals(StartCheck.CONFIRMED, filter.confirmStart(first, s(1_000, 55.000005, 37.0))) // standing still
    }

    @Test
    fun `uninformative next fix keeps the first one pending`() {
        val first = s(1_000, 55.0, 37.0)
        assertEquals(StartCheck.IGNORE, filter.confirmStart(first, s(2_000, 56.0, 37.0, acc = 80f)))
        assertEquals(StartCheck.IGNORE, filter.confirmStart(first, s(1_000, 55.00003, 37.0)))
    }

    /** The recorded reference is wrong (repeated at the start); real fixes walk north 3 m/s from Moscow. */
    private fun walk(from: Int, count: Int) = (from until from + count).map { i -> s(i * 1_000L, 55.8515 + i * 0.000027, 37.47) }

    @Test
    fun `a run of consistent fixes far from the reference moves the track`() {
        val streak = JumpStreak(filter)
        val fixes = walk(1, 5)
        assertEquals(listOf(false, false, false, false, true), fixes.map { streak.onImplausible(it) })
    }

    @Test
    fun `scattered outliers never move the track`() {
        val streak = JumpStreak(filter)
        // each far fix is itself a jump from the previous one
        val scattered = (1..10).map { i -> s(i * 1_000L, if (i % 2 == 0) 10.0 else -10.0, 37.47) }
        assertEquals(false, scattered.any { streak.onImplausible(it) })
    }

    @Test
    fun `an accepted fix in between restarts the count`() {
        val streak = JumpStreak(filter)
        val fixes = walk(1, 8)
        fixes.take(4).forEach { assertEquals(false, streak.onImplausible(it)) }
        streak.reset()
        assertEquals(false, fixes.drop(4).take(4).any { streak.onImplausible(it) })
    }
}

class EffectiveSpeedTest {
    @Test
    fun `reported zero while moving falls back to implied`() {
        assertEquals(3.0f, com.justtracker.app.domain.geo.effectiveSpeed(0f, 3.0f), 0f)
    }

    @Test
    fun `reported zero while stationary stays zero`() {
        assertEquals(0f, com.justtracker.app.domain.geo.effectiveSpeed(0f, 0.2f), 0f)
    }

    @Test
    fun `near-zero reported speed while moving falls back to implied`() {
        assertEquals(3.0f, com.justtracker.app.domain.geo.effectiveSpeed(0.0001f, 3.0f), 0f)
    }

    @Test
    fun `positive reported speed wins`() {
        assertEquals(1.2f, com.justtracker.app.domain.geo.effectiveSpeed(1.2f, 5f), 0f)
    }
}
