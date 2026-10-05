package com.justtracker.app.domain.stats

import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.Track
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Number, distance and moving time of the tracks of a period. */
data class PeriodSummary(val count: Int, val distanceM: Double, val movingTimeMs: Long) {
    companion object {
        val EMPTY = PeriodSummary(0, 0.0, 0)

        fun of(tracks: List<Track>) = PeriodSummary(tracks.size, tracks.sumOf { it.distanceM }, tracks.sumOf { it.movingTimeMs })
    }
}

/** Finished tracks of one activity type, summed in the database. */
data class ActivityTotals(val type: ActivityType, val count: Int, val distanceM: Double, val movingTimeMs: Long)

/** The Statistics screen's figures (docs/03_prd.md US-10): all time, this week, this month, the records. */
data class TrackSummaries(
    val total: PeriodSummary = PeriodSummary.EMPTY,
    val week: PeriodSummary = PeriodSummary.EMPTY,
    val month: PeriodSummary = PeriodSummary.EMPTY,
    val longest: Track? = null,
    val fastest: Track? = null,
) {
    companion object {
        /** Shorter tracks do not compete for the fastest average speed (a short sprint would always win). */
        const val FASTEST_MIN_DISTANCE_M = 500.0

        /** Week from Monday and calendar month of [today] in [zone]; a track belongs to the period it started in. */
        fun of(tracks: List<Track>, today: LocalDate, zone: ZoneId): TrackSummaries {
            val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toEpochMilli()
            val monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
            return TrackSummaries(
                total = PeriodSummary.of(tracks),
                week = PeriodSummary.of(tracks.filter { it.startedAt >= weekStart }),
                month = PeriodSummary.of(tracks.filter { it.startedAt >= monthStart }),
                longest = tracks.maxByOrNull { it.distanceM },
                fastest = tracks.filter { it.distanceM >= FASTEST_MIN_DISTANCE_M }.maxByOrNull { it.avgSpeedMps },
            )
        }
    }
}
