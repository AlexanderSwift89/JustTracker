package com.justtracker.app.domain.activity

import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.stats.TrackStatsCalculator
import kotlin.math.ceil

/**
 * Speed-distribution classifier (docs/06_system_analysis.md §3.5). Rules are evaluated top-down;
 * the first match wins. CAR is checked before RUN/BIKE via the v90 threshold.
 */
object ActivityClassifier {
    const val MIN_MOVING_POINTS = 10

    fun classify(speedsMps: List<Float>): ActivityType {
        val moving = speedsMps.filter { it > TrackStatsCalculator.MOVING_THRESHOLD_MPS }.sorted()
        if (moving.size < MIN_MOVING_POINTS) return ActivityType.UNKNOWN
        val v50 = percentile(moving, 0.5)
        val v90 = percentile(moving, 0.9)
        return when {
            v50 < 2.0 && v90 < 3.0 -> ActivityType.WALK
            v50 >= 8.0 || v90 >= 16.0 -> ActivityType.CAR
            v50 >= 2.0 && v50 < 4.5 && v90 < 6.5 -> ActivityType.RUN
            v50 >= 3.0 && v50 < 12.0 && v90 < 16.0 -> ActivityType.BIKE
            else -> ActivityType.OTHER
        }
    }

    /** Nearest-rank percentile on a sorted list. */
    internal fun percentile(sorted: List<Float>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1].toDouble()
    }
}
