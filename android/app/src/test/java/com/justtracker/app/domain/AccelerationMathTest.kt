package com.justtracker.app.domain

import com.justtracker.app.domain.stats.AccelerationMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccelerationMathTest {
    private fun fit(times: LongArray, speeds: FloatArray, sigma: Float = 0.3f, origin: Long = times.last()) =
        AccelerationMath.fit(times.size, origin, { times[it] }, { speeds[it] }, { AccelerationMath.weight(sigma) })

    @Test
    fun `an exact ramp gives its slope whatever the time origin`() {
        val times = longArrayOf(0, 1_000, 2_000, 3_000, 4_000)
        val speeds = floatArrayOf(10f, 12f, 14f, 16f, 18f)
        assertEquals(2f, fit(times, speeds)!!.mps2, 1e-5f)
        assertEquals(2f, fit(times, speeds, origin = 2_000)!!.mps2, 1e-5f)
    }

    @Test
    fun `sigma of five fixes a second apart is 0,32 sigma v`() {
        val result = fit(longArrayOf(0, 1_000, 2_000, 3_000, 4_000), FloatArray(5) { 10f }, sigma = 0.3f)
        assertEquals(0.0949f, result!!.sigmaMps2, 1e-4f)
    }

    @Test
    fun `a window below 0,5 m per s is standing still`() {
        val result = fit(longArrayOf(0, 1_000, 2_000), floatArrayOf(0.1f, 0.4f, 0.2f))
        assertNotNull(result)
        assertTrue(result!!.stationary)
        assertEquals(0f, result.mps2, 0f)
    }

    @Test
    fun `no estimate when its sigma is too large or the times coincide`() {
        assertNull(fit(longArrayOf(0, 300, 600), floatArrayOf(10f, 10f, 10f), sigma = 1f))
        assertNull(fit(longArrayOf(1_000, 1_000, 1_000), floatArrayOf(10f, 11f, 12f)))
    }

    @Test
    fun `outliers and weights`() {
        assertTrue(AccelerationMath.isOutlier(13f, 1_000))
        assertFalse(AccelerationMath.isOutlier(-11f, 1_000))
        assertEquals(1f / (0.3f * 0.3f), AccelerationMath.weight(0.3f), 1e-3f)
        // Over-optimistic accuracies are floored at 0.05 m/s.
        assertEquals(AccelerationMath.weight(0.05f), AccelerationMath.weight(0.01f), 0f)
    }
}
