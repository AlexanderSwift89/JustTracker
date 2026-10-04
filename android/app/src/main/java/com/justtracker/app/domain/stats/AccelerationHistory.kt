package com.justtracker.app.domain.stats

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Acceleration over the last [size] seconds, oldest first, one value per second; NaN where there was none. Immutable. */
class AccelerationTrace internal constructor(private val values: FloatArray) {
    val size: Int get() = values.size

    operator fun get(index: Int): Float = values[index]

    /** Half-height of a chart drawn symmetrically around 0: at least [MIN_SCALE_MPS2], rounded up to 0.5, so noise stays flat. */
    val scaleMps2: Float
        get() {
            var peak = 0f
            for (v in values) if (!v.isNaN()) peak = max(peak, abs(v))
            return max(MIN_SCALE_MPS2, ceil(peak * 2f) / 2f)
        }

    companion object {
        const val SECONDS = 60
        const val MIN_SCALE_MPS2 = 1f
        val EMPTY = AccelerationTrace(FloatArray(SECONDS) { Float.NaN })
    }
}

/**
 * Ring of per-second acceleration behind [AccelerationTrace] — the "dynamics" of speeding up and slowing down shown
 * next to the live value. The last estimate within a second wins; seconds without one (no fix, no Doppler speed,
 * pause) stay NaN.
 */
class AccelerationHistory(private val seconds: Int = AccelerationTrace.SECONDS) {
    private val ring = FloatArray(seconds) { Float.NaN }
    private var head = 0
    private var lastSecond: Long? = null

    /** [timeMs] on a monotonic clock; [mps2] null when the fix brought no estimate. */
    fun record(timeMs: Long, mps2: Float?) {
        val second = Math.floorDiv(timeMs, 1000L)
        val last = lastSecond
        when {
            last == null || second - last >= seconds -> clearRing()
            second < last -> return
            else -> repeat((second - last).toInt()) {
                head = (head + 1) % seconds
                ring[head] = Float.NaN
            }
        }
        if (mps2 != null) ring[head] = mps2
        lastSecond = second
    }

    fun clear() {
        clearRing()
        lastSecond = null
    }

    fun snapshot(): AccelerationTrace = AccelerationTrace(FloatArray(seconds) { ring[(head + 1 + it) % seconds] })

    private fun clearRing() {
        ring.fill(Float.NaN)
        head = 0
    }
}
