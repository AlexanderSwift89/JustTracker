package com.justtracker.app.domain.geo

data class ElevationResult(val gainM: Double, val lossM: Double)

/**
 * Elevation gain/loss with a centered moving average (window 5) and 3 m hysteresis so that GPS
 * altitude jitter does not accumulate into fake climbing (docs/06_system_analysis.md §3.4).
 */
object ElevationCalculator {
    private const val WINDOW = 5
    private const val THRESHOLD_M = 3.0

    fun gainLoss(altitudes: List<Double>): ElevationResult {
        if (altitudes.size < 2) return ElevationResult(0.0, 0.0)
        val smoothed = movingAverage(altitudes, WINDOW)
        var gain = 0.0
        var loss = 0.0
        var reference = smoothed.first()
        for (i in 1 until smoothed.size) {
            val diff = smoothed[i] - reference
            if (diff >= THRESHOLD_M) {
                gain += diff
                reference = smoothed[i]
            } else if (diff <= -THRESHOLD_M) {
                loss += -diff
                reference = smoothed[i]
            }
        }
        return ElevationResult(gain, loss)
    }

    internal fun movingAverage(values: List<Double>, window: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val half = window / 2
        return values.indices.map { i ->
            val from = maxOf(0, i - half)
            val to = minOf(values.lastIndex, i + half)
            var sum = 0.0
            for (j in from..to) sum += values[j]
            sum / (to - from + 1)
        }
    }
}
