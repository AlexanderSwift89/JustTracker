package com.justtracker.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccelerationColorScaleTest {
    private val palettes = listOf(AccelerationColorScale.LIGHT, AccelerationColorScale.DARK)

    @Test
    fun `steady and no estimate are neutral`() {
        for (p in palettes) {
            for (a in floatArrayOf(0f, 0.2f, -0.24f, Float.NaN)) assertEquals("a=$a", p.neutral, AccelerationColorScale.colorFor(a, 3f, p))
        }
    }

    @Test
    fun `full colour from the scale on, by direction`() {
        for (p in palettes) {
            assertEquals(p.up, AccelerationColorScale.colorFor(3f, 3f, p))
            assertEquals(p.up, AccelerationColorScale.colorFor(9f, 3f, p))
            assertEquals(p.down, AccelerationColorScale.colorFor(-3f, 3f, p))
            assertEquals(p.down, AccelerationColorScale.colorFor(-12f, 3f, p))
        }
    }

    @Test
    fun `intensity grows with the magnitude`() {
        var previous = 0f
        for (i in 0..40) {
            val f = AccelerationColorScale.intensity(i * 0.1f, 3f)
            assertTrue(f >= previous)
            previous = f
        }
        assertEquals(AccelerationColorScale.intensity(1.5f, 3f), AccelerationColorScale.intensity(-1.5f, 3f), 0f)
        // The smallest scale (1 m/s²) still ramps from the steady band.
        assertEquals(1f, AccelerationColorScale.intensity(1f, 1f), 0f)
    }

    @Test
    fun `every colour is opaque, the two themes differ`() {
        for (p in palettes) {
            for (i in -60..60) {
                val c = AccelerationColorScale.colorFor(i * 0.1f, 2.5f, p)
                assertEquals("a=${i * 0.1f}", 0xFF, (c ushr 24) and 0xFF)
            }
        }
        assertNotEquals(AccelerationColorScale.LIGHT.up, AccelerationColorScale.DARK.up)
        assertNotEquals(AccelerationColorScale.LIGHT.down, AccelerationColorScale.DARK.down)
    }

    @Test
    fun `speeding up, slowing down and steady stay apart with simulated colour-vision deficiency`() {
        // Machado, Oliveira, Fernandes 2009, severity 1, in linear RGB; distances in CIELAB (ΔE76).
        val deuteranopia = doubleArrayOf(0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.011820, 0.042940, 0.968881)
        val protanopia = doubleArrayOf(0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998)
        val normal = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        for (p in palettes) {
            for (m in listOf(normal, deuteranopia, protanopia)) {
                val up = lab(simulate(p.up, m))
                val down = lab(simulate(p.down, m))
                val neutral = lab(simulate(p.neutral, m))
                assertTrue("up/down ${distance(up, down)}", distance(up, down) >= 20.0)
                assertTrue("up/neutral ${distance(up, neutral)}", distance(up, neutral) >= 30.0)
                assertTrue("down/neutral ${distance(down, neutral)}", distance(down, neutral) >= 30.0)
            }
        }
    }

    private fun linear(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun simulate(argb: Int, m: DoubleArray): DoubleArray {
        val rgb = doubleArrayOf(linear((argb shr 16) and 0xFF), linear((argb shr 8) and 0xFF), linear(argb and 0xFF))
        return DoubleArray(3) { r -> (m[3 * r] * rgb[0] + m[3 * r + 1] * rgb[1] + m[3 * r + 2] * rgb[2]).coerceIn(0.0, 1.0) }
    }

    private fun lab(rgb: DoubleArray): DoubleArray {
        val x = (0.4124 * rgb[0] + 0.3576 * rgb[1] + 0.1805 * rgb[2]) / 0.95047
        val y = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
        val z = (0.0193 * rgb[0] + 0.1192 * rgb[1] + 0.9505 * rgb[2]) / 1.08883
        fun f(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double =
        Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
}
