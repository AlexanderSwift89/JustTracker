package com.justtracker.app.domain.stats

/**
 * What the recording screen and the notification show about the motion right now, fed with every fix rather than only
 * with those the track keeps. `LocationFilter` drops fixes that moved less than 2 m within 30 s, so a speed taken from
 * stored points updated every 2–3 s at walking pace and stayed at its last moving value for up to 30 s after a stop
 * (OBS-12).
 *
 * The receiver's Doppler ground speed is used while it can be trusted ([hasDopplerSpeed]); otherwise the caller keeps
 * showing the speed of stored points ([IncrementalStats.currentSpeedMps]) as before — e.g. on the emulator, which
 * reports 0 while the position moves (D-02). The same trusted speeds give the along-track [acceleration] and its last
 * minute ([trace], ADR-20).
 */
class LiveMotion {
    private val estimator = AccelerationEstimator()
    private val history = AccelerationHistory()
    private var smoothedSpeed = 0f
    private var lastDopplerAt: Long? = null
    private var latestFixAt: Long? = null
    private var distrustedUntil = Long.MIN_VALUE

    /** Doppler speed smoothed like [IncrementalStats.currentSpeedMps] (alpha 0.5); meaningful while [hasDopplerSpeed]. */
    val speedMps: Float get() = smoothedSpeed

    /** Along-track acceleration; null while there is none or the receiver's speed has not been usable for [STALE_MS]. */
    val acceleration: Acceleration?
        get() = estimator.current?.takeIf { latestFixAt?.let(::hasDopplerSpeed) == true }

    /** True when a trusted Doppler speed arrived within [STALE_MS] before [timeMs]. */
    fun hasDopplerSpeed(timeMs: Long): Boolean = lastDopplerAt?.let { timeMs - it <= STALE_MS } ?: false

    /** Acceleration over the last minute, one value per second. */
    fun trace(): AccelerationTrace = history.snapshot()

    /**
     * Every fix: [timeMs] on a monotonic clock, [speedMps] the receiver's ground speed (null when it reports none) with
     * its 68 % [speedAccuracyMps] (null when unknown), [accurate] — the position passed the accuracy gate.
     */
    fun onFix(timeMs: Long, speedMps: Float?, speedAccuracyMps: Float?, accurate: Boolean) {
        latestFixAt = timeMs
        val speed = speedMps?.takeIf {
            accurate && timeMs >= distrustedUntil && (speedAccuracyMps == null || speedAccuracyMps <= MAX_SPEED_ACCURACY_MPS)
        }
        if (speed == null) {
            history.record(timeMs, null)
            return
        }
        smoothedSpeed = if (hasDopplerSpeed(timeMs)) 0.5f * smoothedSpeed + 0.5f * speed else speed
        lastDopplerAt = timeMs
        val estimate = estimator.offer(timeMs, speed, speedAccuracyMps ?: DEFAULT_SPEED_ACCURACY_MPS)
        history.record(timeMs, estimate?.mps2)
    }

    /**
     * A fix the track stored at [effectiveMps] (`effectiveSpeed`). When the receiver reported standing still while the
     * position clearly moved, its speed is not trusted for [DISTRUST_MS].
     */
    fun onRecorded(timeMs: Long, reportedMps: Float?, effectiveMps: Float) {
        if (reportedMps != null && reportedMps <= TrackStatsCalculator.MOVING_THRESHOLD_MPS && effectiveMps > CONTRADICTION_MPS) {
            distrustedUntil = timeMs + DISTRUST_MS
            lastDopplerAt = null
            estimator.reset()
        }
    }

    /** Pause and resume: nothing is carried across the gap; the last minute stays, the gap shows in it as empty seconds. */
    fun reset() {
        estimator.reset()
        smoothedSpeed = 0f
        lastDopplerAt = null
        latestFixAt = null
        distrustedUntil = Long.MIN_VALUE
    }

    companion object {
        const val STALE_MS = AccelerationEstimator.MAX_GAP_MS
        const val MAX_SPEED_ACCURACY_MPS = 1.0f

        /** Assumed when the receiver gives no speed accuracy: a typical phone in town. */
        const val DEFAULT_SPEED_ACCURACY_MPS = 0.3f

        /** A stored point this fast while the receiver claimed ≤ 0.5 m/s: its Doppler speed is wrong. */
        const val CONTRADICTION_MPS = 1.0f
        const val DISTRUST_MS = 10_000L
    }
}
