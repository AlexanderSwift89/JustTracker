package com.justtracker.app.domain

import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.TrackStatus
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.stats.SpeedProfile
import com.justtracker.app.domain.stats.TrackStatsCalculator
import com.justtracker.app.ui.common.SpeedColorScale
import com.justtracker.app.ui.common.TrackPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedProfileTest {
    @Test
    fun `empty input gives empty profile`() {
        val p = SpeedProfile.of(emptyList())
        assertEquals(0, p.size)
        assertEquals(0.0, p.maxSpeedMps, 0.0)
        assertTrue(TrackPath.build(emptyList()).isEmpty())
    }

    @Test
    fun `speeds match the calculator and distance accumulates`() {
        val points = syntheticTrack(speedMps = 3.0, seconds = 20)
        val p = SpeedProfile.of(points)
        assertEquals(points.size, p.size)
        assertEquals(TrackStatsCalculator.smoothedSpeeds(points), p.speedsMps.toList())
        assertEquals(0.0, p.distanceM.first(), 0.0)
        assertEquals(3.0 * 19, p.distanceM.last(), 0.5)
        assertEquals(3.0, p.maxSpeedMps, 0.05)
        assertEquals(points.map { it.timestamp }, p.timestamps.toList())
    }

    @Test
    fun `distance does not jump across a segment break`() {
        val a = syntheticTrack(speedMps = 2.0, seconds = 10, segment = 0)
        val b = syntheticTrack(speedMps = 2.0, seconds = 10, segment = 1, startTime = 60_000).map { it.copy(lat = it.lat + 0.1) }
        val p = SpeedProfile.of(a + b)
        // 9 intervals in each segment, the 10 km gap between them is ignored
        assertEquals(2.0 * 9, p.distanceM[9], 0.5)
        assertEquals(2.0 * 9, p.distanceM[10], 0.5)
        assertEquals(2.0 * 18, p.distanceM.last(), 0.5)
    }

    @Test
    fun `slow and fast sections keep their own speed`() {
        val slow = syntheticTrack(speedMps = 1.0, seconds = 30, segment = 0)
        val fast = syntheticTrack(speedMps = 10.0, seconds = 30, segment = 0, startTime = 30_000).map { it.copy(lat = it.lat + slow.last().lat) }
        val p = SpeedProfile.of(slow + fast)
        assertEquals(1.0, p.speedsMps[10].toDouble(), 0.1)
        assertEquals(10.0, p.speedsMps[50].toDouble(), 0.1)
    }

    @Test
    fun `path segments are split by segment number and resolve taps`() {
        val a = syntheticTrack(speedMps = 2.0, seconds = 5, segment = 0)
        val b = syntheticTrack(speedMps = 4.0, seconds = 5, segment = 1, startTime = 30_000)
        val segments = TrackPath.build(a + b)
        assertEquals(2, segments.size)
        assertEquals(5, segments[0].size)
        assertEquals(5, segments[1].size)
        assertEquals(b[2].timestamp, segments[1].timestamps[2])

        val tap = TrackPath.tapInfo(segments, segment = 1, index = 2, startedAt = 0L)
        assertNotNull(tap)
        assertEquals(32_000L, tap!!.elapsedMs)
        assertEquals(4.0, tap.speedMps.toDouble(), 0.1)
        assertEquals(2.0 * 4 + 4.0 * 2, tap.distanceFromStartM, 0.5)
        assertEquals(b[2].lat, tap.point.latitude, 1e-9)

        assertNull(TrackPath.tapInfo(segments, segment = 2, index = 0, startedAt = 0L))
        assertNull(TrackPath.tapInfo(segments, segment = 0, index = 5, startedAt = 0L))
    }

    @Test
    fun `cursor addresses vertices across segments and reports altitude`() {
        val a = syntheticTrack(speedMps = 2.0, seconds = 5, segment = 0, altitude = { 100.0 + it })
        val b = syntheticTrack(speedMps = 4.0, seconds = 5, segment = 1, startTime = 30_000)
        val segments = TrackPath.build(a + b)
        assertEquals(10, TrackPath.pointCount(segments))
        assertEquals(2.0 * 4 + 4.0 * 4, TrackPath.totalDistanceM(segments), 0.5)
        assertEquals(7, TrackPath.globalIndex(segments, segment = 1, index = 2))

        val c0 = TrackPath.cursorAt(segments, 0, startedAt = 0L)!!
        assertEquals(0f, c0.fraction, 0f)
        assertEquals(103.0, TrackPath.cursorAt(segments, 3, 0L)!!.altitudeM!!, 1e-9)
        val c7 = TrackPath.cursorAt(segments, 7, startedAt = 0L)!!
        assertEquals(32_000L, c7.elapsedMs)
        assertEquals(b[2].timestamp, c7.timestamp)
        assertNull(c7.altitudeM)
        assertEquals((8.0 + 8.0) / 24.0, c7.fraction.toDouble(), 0.02)
        val last = TrackPath.cursorAt(segments, 9, 0L)!!
        assertEquals(1f, last.fraction, 1e-6f)
        assertNull(TrackPath.cursorAt(segments, 10, 0L))
        assertNull(TrackPath.cursorAt(segments, -1, 0L))
    }

    @Test
    fun `slider fraction maps to the nearest vertex by distance`() {
        val a = syntheticTrack(speedMps = 2.0, seconds = 5, segment = 0) // 0,2,4,6,8 m
        val b = syntheticTrack(speedMps = 4.0, seconds = 5, segment = 1, startTime = 30_000) // 8,12,16,20,24 m
        val segments = TrackPath.build(a + b)
        assertEquals(0, TrackPath.indexForFraction(segments, 0f))
        assertEquals(9, TrackPath.indexForFraction(segments, 1f))
        assertEquals(2, TrackPath.indexForFraction(segments, 4f / 24f)) // exactly vertex 2
        assertEquals(1, TrackPath.indexForFraction(segments, 2.6f / 24f)) // 2.6 m → nearer to 2 m than 4 m
        assertEquals(7, TrackPath.indexForFraction(segments, 15f / 24f)) // 15 m → 16 m vertex in segment 1
        assertEquals(9, TrackPath.indexForFraction(segments, 2f)) // clamped
        assertTrue(TrackPath.indexForFraction(emptyList(), 0.5f) == 0)
    }
}

class SpeedColorScaleTest {
    @Test
    fun `fraction is relative to max and clamped`() {
        assertEquals(0f, SpeedColorScale.fraction(0f, 10.0), 0f)
        assertEquals(0.5f, SpeedColorScale.fraction(5f, 10.0), 1e-6f)
        assertEquals(1f, SpeedColorScale.fraction(12f, 10.0), 0f)
        assertEquals(0f, SpeedColorScale.fraction(-1f, 10.0), 0f)
    }

    @Test
    fun `tiny max uses the minimum range so a slow track is not all red`() {
        assertEquals(0.3f, SpeedColorScale.fraction(0.3f, 0.2), 1e-6f)
    }

    @Test
    fun `colour endpoints are the first and last stops`() {
        assertEquals(SpeedColorScale.STOPS.first(), SpeedColorScale.colorFor(0f))
        assertEquals(SpeedColorScale.STOPS.last(), SpeedColorScale.colorFor(1f))
        assertEquals(SpeedColorScale.STOPS[2], SpeedColorScale.colorFor(0.5f))
    }

    @Test
    fun `interpolated colour is opaque and between the stops`() {
        val c = SpeedColorScale.colorFor(0.125f)
        assertEquals(0xFF, (c ushr 24) and 0xFF)
        val r = (c shr 16) and 0xFF
        val r0 = (SpeedColorScale.STOPS[0] shr 16) and 0xFF
        val r1 = (SpeedColorScale.STOPS[1] shr 16) and 0xFF
        assertTrue(r in minOf(r0, r1)..maxOf(r0, r1))
    }
}

class TrackRecordingTimeTest {
    private fun track(startedAt: Long, finishedAt: Long?, paused: Long = 0) = Track(
        name = "t",
        status = if (finishedAt == null) TrackStatus.RECORDING else TrackStatus.FINISHED,
        activityType = ActivityType.WALK,
        activityManual = false,
        startedAt = startedAt,
        finishedAt = finishedAt,
        pausedTimeMs = paused,
    )

    @Test
    fun `finished track counts start to stop including pauses`() {
        assertEquals(90_000L, track(10_000, 100_000, paused = 20_000).recordingTimeMs(nowMs = 999_999))
    }

    @Test
    fun `active track counts up to now`() {
        assertEquals(25_000L, track(10_000, null).recordingTimeMs(nowMs = 35_000))
    }

    @Test
    fun `never negative`() {
        assertEquals(0L, track(50_000, null).recordingTimeMs(nowMs = 40_000))
    }
}
