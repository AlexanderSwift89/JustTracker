package io.treklog.app.domain

import io.treklog.app.domain.geo.FilterResult
import io.treklog.app.domain.geo.Geo
import io.treklog.app.domain.geo.LocationFilter
import io.treklog.app.domain.geo.Sample
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
}

class EffectiveSpeedTest {
    @Test
    fun `reported zero while moving falls back to implied`() {
        assertEquals(3.0f, io.treklog.app.domain.geo.effectiveSpeed(0f, 3.0f), 0f)
    }

    @Test
    fun `reported zero while stationary stays zero`() {
        assertEquals(0f, io.treklog.app.domain.geo.effectiveSpeed(0f, 0.2f), 0f)
    }

    @Test
    fun `near-zero reported speed while moving falls back to implied`() {
        assertEquals(3.0f, io.treklog.app.domain.geo.effectiveSpeed(0.0001f, 3.0f), 0f)
    }

    @Test
    fun `positive reported speed wins`() {
        assertEquals(1.2f, io.treklog.app.domain.geo.effectiveSpeed(1.2f, 5f), 0f)
    }
}
