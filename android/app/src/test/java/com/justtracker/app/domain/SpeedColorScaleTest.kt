package com.justtracker.app.domain

import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.TrackStatus
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.ui.common.SpeedColorScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
