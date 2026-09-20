package com.justtracker.app.ui.common

/**
 * Maps a speed to a line colour relative to the track's own maximum (docs/04_ux_design.md §2.8):
 * 0 → blue … max → red. Relative rather than absolute so a walk and a drive both show where the
 * fast and slow sections were. Pure Kotlin (ARGB ints) so it is unit-testable on the JVM.
 */
object SpeedColorScale {
    /** Colour stops from slowest to fastest, ARGB. */
    val STOPS: IntArray = intArrayOf(
        0xFF1E88E5.toInt(), // blue
        0xFF43A047.toInt(), // green
        0xFFFDD835.toInt(), // yellow
        0xFFFB8C00.toInt(), // orange
        0xFFE53935.toInt(), // red
    )

    /** Scale top is never below this so a stationary/very slow track is not painted all red. */
    const val MIN_RANGE_MPS = 1.0

    /** Position of [speedMps] on the 0…[maxMps] scale, clamped to 0..1. */
    fun fraction(speedMps: Float, maxMps: Double): Float {
        val top = maxOf(maxMps, MIN_RANGE_MPS)
        return (speedMps / top).toFloat().coerceIn(0f, 1f)
    }

    /** Linear interpolation between [STOPS] for a fraction in 0..1. */
    fun colorFor(fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f) * (STOPS.size - 1)
        val i = f.toInt().coerceAtMost(STOPS.size - 2)
        return lerp(STOPS[i], STOPS[i + 1], f - i)
    }

    fun colorForSpeed(speedMps: Float, maxMps: Double): Int = colorFor(fraction(speedMps, maxMps))

    private fun lerp(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * t + 0.5f).toInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
