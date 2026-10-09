package com.justtracker.app.ui.common

import com.justtracker.app.domain.stats.AccelerationEstimator
import kotlin.math.abs

/**
 * Colours of acceleration (docs/04_ux_design.md §2.12, §2.13): speeding up, slowing down and the neutral "steady" —
 * the live indicator's arrow and bars and a finished track's line coloured by acceleration. Fixed hues rather than
 * dynamic-color roles, so the meaning never changes with the wallpaper. Every colour is opaque: osmdroid draws each
 * segment of the line on its own with round caps, so a translucent colour would darken every joint. Pure Kotlin
 * (ARGB ints) so it is unit-testable on the JVM.
 */
object AccelerationColorScale {
    class Palette(val up: Int, val down: Int, val neutral: Int)

    /** On light tiles and the light panel. */
    val LIGHT = Palette(up = 0xFF2E7D32.toInt(), down = 0xFFE65100.toInt(), neutral = 0xFF78909C.toInt())

    /**
     * On inverted (dark) tiles and the dark panel. The green is darker than the panel's former #81C784: against the
     * orange it then differs in lightness too, which is what tells them apart with deuteranopia.
     */
    val DARK = Palette(up = 0xFF4CAF50.toInt(), down = 0xFFFFB74D.toInt(), neutral = 0xFF90A4AE.toInt())

    fun palette(dark: Boolean): Palette = if (dark) DARK else LIGHT

    /** Inside ±this the motion reads as steady: the neutral colour, as the live chart's grey bars. */
    const val STEADY_MPS2 = AccelerationEstimator.ENTER_MPS2

    /** 0 inside the steady band, rising to 1 at ±[scaleMps2] (≥ 1 m/s²). */
    fun intensity(mps2: Float, scaleMps2: Float): Float {
        val m = abs(mps2)
        if (mps2.isNaN() || m < STEADY_MPS2) return 0f
        return ((m - STEADY_MPS2) / (scaleMps2 - STEADY_MPS2).coerceAtLeast(STEADY_MPS2)).coerceIn(0f, 1f)
    }

    /** Neutral → speeding-up / slowing-down colour by the [intensity]; no estimate (NaN) is neutral. */
    fun colorFor(mps2: Float, scaleMps2: Float, palette: Palette): Int {
        val f = intensity(mps2, scaleMps2)
        if (f == 0f) return palette.neutral
        return lerpArgb(palette.neutral, if (mps2 > 0) palette.up else palette.down, f)
    }
}
