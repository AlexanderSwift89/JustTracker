package com.justtracker.app.domain

import com.justtracker.app.domain.stats.LiveMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMotionTest {
    private fun LiveMotion.drive(speeds: List<Float>, startMs: Long = 0, sigma: Float? = 0.2f) {
        speeds.forEachIndexed { i, v -> onFix(startMs + i * 1_000L, v, sigma, accurate = true) }
    }

    @Test
    fun `every fix updates the speed, not only stored points`() {
        val motion = LiveMotion()
        motion.drive(listOf(1.4f, 1.4f, 1.4f))
        assertTrue(motion.hasDopplerSpeed(2_000))
        assertEquals(1.4f, motion.speedMps, 0.001f)
    }

    @Test
    fun `after a stop the speed falls to zero within seconds, not 30 s`() {
        val motion = LiveMotion()
        motion.drive(listOf(10f, 7f, 4f, 1f, 0f, 0f, 0f, 0f))
        // 4 s after standing still the shown speed is below 0.5 m/s (it stayed at the last moving value before, OBS-12).
        assertTrue("speed ${motion.speedMps}", motion.speedMps < 0.5f)
    }

    @Test
    fun `without Doppler speed the caller keeps the stored-point speed`() {
        val motion = LiveMotion()
        motion.onFix(0, null, null, accurate = true)
        assertFalse(motion.hasDopplerSpeed(0))
    }

    @Test
    fun `inaccurate fixes and poor speed accuracy are ignored`() {
        val motion = LiveMotion()
        motion.onFix(0, 5f, 0.2f, accurate = false)
        motion.onFix(1_000, 5f, 1.5f, accurate = true)
        assertFalse(motion.hasDopplerSpeed(1_000))
        motion.onFix(2_000, 5f, null, accurate = true) // unknown accuracy is fine
        assertTrue(motion.hasDopplerSpeed(2_000))
    }

    @Test
    fun `a receiver reporting zero while moving is not trusted for 10 s`() {
        val motion = LiveMotion()
        motion.drive(listOf(0f, 0f), sigma = null)
        motion.onRecorded(1_000, reportedMps = 0f, effectiveMps = 3f)
        assertFalse(motion.hasDopplerSpeed(1_000))
        motion.onFix(5_000, 0f, null, accurate = true)
        assertFalse(motion.hasDopplerSpeed(5_000))
        motion.onFix(11_000, 3f, null, accurate = true)
        assertTrue(motion.hasDopplerSpeed(11_000))
        assertEquals(3f, motion.speedMps, 0f)
    }

    @Test
    fun `a stored point agreeing with the receiver keeps it trusted`() {
        val motion = LiveMotion()
        motion.drive(listOf(3f, 3f))
        motion.onRecorded(1_000, reportedMps = 3f, effectiveMps = 3f)
        motion.onRecorded(1_000, reportedMps = 0.2f, effectiveMps = 0.4f) // slow walk, not a contradiction
        assertTrue(motion.hasDopplerSpeed(1_000))
    }

    @Test
    fun `acceleration comes from trusted speed and is dropped once the receiver is distrusted`() {
        val motion = LiveMotion()
        motion.drive(listOf(5f, 6f, 7f, 8f, 9f))
        assertEquals(1f, motion.acceleration!!.mps2, 0.05f)
        motion.onRecorded(4_000, reportedMps = 0.3f, effectiveMps = 9f)
        assertEquals(null, motion.acceleration)
    }

    @Test
    fun `acceleration expires when fixes stop carrying a speed`() {
        val motion = LiveMotion()
        motion.drive(listOf(5f, 6f, 7f, 8f))
        motion.onFix(5_000, null, null, accurate = true)
        assertEquals(1f, motion.acceleration!!.mps2, 0.05f) // one fix without speed: the estimate still stands
        motion.onFix(7_000, null, null, accurate = true)
        assertEquals(null, motion.acceleration)
    }

    @Test
    fun `the last minute holds one value per second and empty seconds without speed`() {
        val motion = LiveMotion()
        motion.drive(listOf(5f, 6f, 7f, 8f))
        motion.onFix(4_000, 9f, 0.2f, accurate = false)
        val trace = motion.trace()
        assertTrue(trace[trace.size - 1].isNaN())
        assertEquals(1f, trace[trace.size - 2], 0.05f)
        assertTrue(trace[trace.size - 5].isNaN()) // the first two fixes give no estimate yet
    }

    @Test
    fun `smoothing restarts after a gap and after a reset`() {
        val motion = LiveMotion()
        motion.drive(listOf(10f, 10f))
        assertFalse(motion.hasDopplerSpeed(5_000))
        motion.onFix(5_000, 2f, 0.2f, accurate = true)
        assertEquals(2f, motion.speedMps, 0f)
        motion.reset()
        assertFalse(motion.hasDopplerSpeed(5_000))
        assertEquals(0f, motion.speedMps, 0f)
    }
}
