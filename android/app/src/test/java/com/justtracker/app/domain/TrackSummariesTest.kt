package com.justtracker.app.domain

import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.TrackStatus
import com.justtracker.app.domain.stats.PeriodSummary
import com.justtracker.app.domain.stats.TrackSummaries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TrackSummariesTest {
    private val zone = ZoneId.of("Europe/Moscow")

    private fun track(name: String, started: LocalDateTime, distanceM: Double, avgMps: Double = 1.0) = Track(
        name = name, status = TrackStatus.FINISHED, activityType = ActivityType.WALK, activityManual = false,
        startedAt = started.atZone(zone).toInstant().toEpochMilli(), finishedAt = null,
        distanceM = distanceM, movingTimeMs = 600_000, avgSpeedMps = avgMps,
    )

    @Test
    fun `week starts on Monday and month on the 1st, in the given zone`() {
        val today = LocalDate.of(2026, 10, 7) // Wednesday
        val tracks = listOf(
            track("sunday before", LocalDateTime.of(2026, 10, 4, 23, 30), 1000.0),
            track("monday 00:10", LocalDateTime.of(2026, 10, 5, 0, 10), 2000.0),
            track("today", LocalDateTime.of(2026, 10, 7, 8, 0), 3000.0),
            track("september", LocalDateTime.of(2026, 9, 30, 12, 0), 4000.0),
        )
        val s = TrackSummaries.of(tracks, today, zone)
        assertEquals(PeriodSummary(4, 10_000.0, 2_400_000), s.total)
        assertEquals(2, s.week.count)
        assertEquals(5000.0, s.week.distanceM, 0.0)
        assertEquals(3, s.month.count)
        assertEquals("september", s.longest!!.name)
    }

    @Test
    fun `the fastest track needs 500 m`() {
        val today = LocalDate.of(2026, 10, 7)
        val sprint = track("sprint", LocalDateTime.of(2026, 10, 6, 9, 0), 300.0, avgMps = 8.0)
        val run = track("run", LocalDateTime.of(2026, 10, 6, 10, 0), 5000.0, avgMps = 3.0)
        assertEquals("run", TrackSummaries.of(listOf(sprint, run), today, zone).fastest!!.name)
        assertNull(TrackSummaries.of(listOf(sprint), today, zone).fastest)
    }

    @Test
    fun `no tracks give empty summaries`() {
        val s = TrackSummaries.of(emptyList(), LocalDate.of(2026, 10, 7), zone)
        assertEquals(PeriodSummary.EMPTY, s.total)
        assertNull(s.longest)
    }
}
