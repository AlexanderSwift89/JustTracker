package com.justtracker.app.domain.stats

/** Whether the user is speeding up or slowing down, decided with hysteresis so it does not flicker (docs/06_system_analysis.md §3.10). */
enum class AccelerationState { ACCELERATING, DECELERATING, STEADY, STATIONARY }

/** Along-track horizontal acceleration — the rate of change of ground speed, m/s² (> 0 speeding up) — with its 1σ. */
data class Acceleration(val mps2: Float, val sigmaMps2: Float, val state: AccelerationState)

/**
 * Horizontal acceleration from the receiver's Doppler ground speed (ADR-20): the weighted least-squares slope of the
 * speed over the last [windowMs], weights 1/σv². Differentiating a position-derived speed would turn metres of
 * position noise into m/s², so only reported speed is fed here — from every usable fix, not only from those the track
 * keeps (OBS-12).
 *
 * Rules (docs/06_system_analysis.md §3.10):
 * - a fix not newer than the previous one is ignored; a gap longer than [maxGapMs] restarts the window;
 * - a speed step implying more than [MAX_PLAUSIBLE_MPS2] is an outlier; two in a row restart the window;
 * - no estimate until the window holds [MIN_SAMPLES] fixes over at least [minSpanMs], nor when its σ exceeds [MAX_SIGMA_MPS2];
 * - every speed in the window below [STATIONARY_MPS] means standing still: 0, as the noise of a speed magnitude has no
 *   meaningful slope.
 */
class AccelerationEstimator(
    private val windowMs: Long = WINDOW_MS,
    private val minSpanMs: Long = MIN_SPAN_MS,
    private val maxGapMs: Long = MAX_GAP_MS,
) {
    private val times = LongArray(CAPACITY)
    private val speeds = FloatArray(CAPACITY)
    private val weights = FloatArray(CAPACITY)
    private var first = 0
    private var count = 0
    private var outliersInRow = 0
    private var state = AccelerationState.STEADY

    /** Latest estimate; null while there is none. */
    var current: Acceleration? = null
        private set

    fun reset() {
        first = 0
        count = 0
        outliersInRow = 0
        state = AccelerationState.STEADY
        current = null
    }

    /**
     * Feeds one fix: [timeMs] on a monotonic clock, [speedMps] the reported ground speed, [sigmaMps] its 68 % accuracy.
     * Returns the estimate after it, null when there is none.
     */
    fun offer(timeMs: Long, speedMps: Float, sigmaMps: Float): Acceleration? {
        if (count > 0) {
            val last = slot(count - 1)
            val dtMs = timeMs - times[last]
            if (dtMs <= 0) return current
            if (dtMs > maxGapMs) {
                reset()
            } else if (AccelerationMath.isOutlier(speedMps - speeds[last], dtMs)) {
                // One impossible step is the receiver's glitch; a second one in a row means the speed really is elsewhere.
                if (++outliersInRow < 2) return current
                reset()
            }
        }
        outliersInRow = 0
        add(timeMs, speedMps, AccelerationMath.weight(sigmaMps))
        while (timeMs - times[first] > windowMs) drop()
        current = estimate(timeMs)
        return current
    }

    private fun estimate(nowMs: Long): Acceleration? {
        if (count < MIN_SAMPLES || times[slot(count - 1)] - times[first] < minSpanMs) return null
        val fit = AccelerationMath.fit(count, nowMs, { times[slot(it)] }, { speeds[slot(it)] }, { weights[slot(it)] }) ?: return null
        state = if (fit.stationary) AccelerationState.STATIONARY else nextState(state, fit.mps2)
        return Acceleration(fit.mps2, fit.sigmaMps2, state)
    }

    private fun add(timeMs: Long, speedMps: Float, weight: Float) {
        if (count == CAPACITY) drop()
        val k = slot(count)
        times[k] = timeMs
        speeds[k] = speedMps
        weights[k] = weight
        count++
    }

    private fun drop() {
        first = (first + 1) % CAPACITY
        count--
    }

    private fun slot(i: Int) = (first + i) % CAPACITY

    companion object {
        const val WINDOW_MS = 4_000L
        const val MIN_SPAN_MS = 2_000L
        const val MAX_GAP_MS = 3_000L
        const val MIN_SAMPLES = 3

        /** > 1.2 g along the ground: beyond any sprint start or emergency stop. */
        const val MAX_PLAUSIBLE_MPS2 = 12f
        const val MAX_SIGMA_MPS2 = 0.5f
        const val STATIONARY_MPS = TrackStatsCalculator.MOVING_THRESHOLD_MPS

        /** Hysteresis: a direction is entered at ±[ENTER_MPS2] and kept until the value falls inside ±[EXIT_MPS2]. */
        const val ENTER_MPS2 = 0.25f
        const val EXIT_MPS2 = 0.12f

        /** Floor for the reported speed accuracy so one over-optimistic fix cannot outweigh the rest of the window. */
        const val MIN_SIGMA_MPS = 0.05f

        /** Room for a 4 s window at up to 4 Hz; older fixes give way first. */
        private const val CAPACITY = 16

        fun nextState(previous: AccelerationState, mps2: Float): AccelerationState = when {
            mps2 >= ENTER_MPS2 -> AccelerationState.ACCELERATING
            mps2 <= -ENTER_MPS2 -> AccelerationState.DECELERATING
            previous == AccelerationState.ACCELERATING && mps2 >= EXIT_MPS2 -> AccelerationState.ACCELERATING
            previous == AccelerationState.DECELERATING && mps2 <= -EXIT_MPS2 -> AccelerationState.DECELERATING
            else -> AccelerationState.STEADY
        }
    }
}
