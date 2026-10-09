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
}
