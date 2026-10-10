package com.justtracker.app.domain

import com.justtracker.app.domain.stats.Acceleration
import com.justtracker.app.domain.stats.AccelerationEstimator
import com.justtracker.app.domain.stats.AccelerationHistory
import com.justtracker.app.domain.stats.AccelerationState
import com.justtracker.app.domain.stats.AccelerationState.ACCELERATING
import com.justtracker.app.domain.stats.AccelerationState.DECELERATING
import com.justtracker.app.domain.stats.AccelerationState.STATIONARY
import com.justtracker.app.domain.stats.AccelerationState.STEADY
import com.justtracker.app.domain.stats.AccelerationTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class AccelerationEstimatorTest {
    /** Feeds (t ms, v m/s) pairs with one accuracy; returns the estimate after each fix. */
    private fun AccelerationEstimator.feed(fixes: List<Pair<Long, Float>>, sigma: Float = 0.2f): List<Acceleration?> =
        fixes.map { (t, v) -> offer(t, v, sigma) }

    private fun series(seconds: Int, stepMs: Long = 1_000, speed: (Double) -> Double): List<Pair<Long, Float>> =
        (0 until seconds).map { i -> val t = i * stepMs; t to speed(t / 1000.0).toFloat() }

    @Test
    fun `constant speed with GPS noise reads as steady`() {
        val rnd = Random(42)
        val estimates = AccelerationEstimator().feed(series(120) { 10.0 + rnd.nextDouble(-1.0, 1.0) * 0.35 }).filterNotNull()
        val small = estimates.count { abs(it.mps2) < 0.25f }
        assertTrue("only $small of ${estimates.size} near zero", small >= estimates.size * 0.95)
        assertTrue(estimates.all { it.state == STEADY })
    }

    @Test
    fun `steady speeding up gives its slope`() {
        val estimates = AccelerationEstimator().feed(series(10) { 2.0 + 2.0 * it })
        for (a in estimates.drop(4)) {
            assertNotNull(a)
            assertEquals(2.0f, a!!.mps2, 0.15f)
            assertEquals(ACCELERATING, a.state)
        }
    }

    @Test
    fun `hard braking then standing still`() {
        // 30 m/s braking at 6 m/s² to a stop in 5 s, then 6 s of a stationary receiver's speed noise.
        val braking = series(6) { maxOf(0.0, 30.0 - 6.0 * it) }
        val stop = (6 until 12).map { i -> i * 1_000L to (if (i % 2 == 0) 0.3f else 0.1f) }
        val estimator = AccelerationEstimator()
        val estimates = estimator.feed(braking + stop)
        val atFullWindow = estimates[4]!!
        assertEquals(-6.0f, atFullWindow.mps2, 0.3f)
        assertEquals(DECELERATING, atFullWindow.state)
        val last = estimates.last()!!
        assertEquals(STATIONARY, last.state)
        assertEquals(0f, last.mps2, 0f)
    }

    @Test
    fun `a sudden start shows half of it within 2,5 s`() {
        // 5 m/s for 6 s, then +2 m/s² from t = 6 s; fixes every 500 ms.
        val fixes = series(30, stepMs = 500) { t -> if (t < 6.0) 5.0 else 5.0 + 2.0 * (t - 6.0) }
        val estimates = AccelerationEstimator().feed(fixes)
        val firstHalf = fixes.indices.first { estimates[it] != null && estimates[it]!!.mps2 >= 1.0f }
        assertTrue("reached half at ${fixes[firstHalf].first} ms", fixes[firstHalf].first - 6_000 <= 2_500)
    }

    @Test
    fun `standing still with a noisy speed reads zero`() {
        val rnd = Random(7)
        val estimates = AccelerationEstimator().feed(series(30) { abs(rnd.nextDouble(-0.4, 0.4)) }).filterNotNull()
        assertTrue(estimates.isNotEmpty())
        assertTrue(estimates.all { it.state == STATIONARY && it.mps2 == 0f })
    }

    @Test
    fun `irregular intervals still give the slope`() {
        val steps = longArrayOf(500, 1_500, 1_000, 2_000, 700, 1_300, 900, 1_100, 1_800, 600)
        var t = 0L
        val fixes = buildList {
            add(0L to 3f)
            for (dt in steps) {
                t += dt
                add(t to (3.0 + 1.5 * t / 1000.0).toFloat())
            }
        }
        val last = AccelerationEstimator().feed(fixes).last()
        assertEquals(1.5f, last!!.mps2, 0.05f)
    }

    @Test
    fun `fixes every 2 s with jittering times still give the slope (D-41)`() {
        // The emulator's fused provider and phones saving power deliver every 2 s: the third fix back is 4.01 s old.
        val times = longArrayOf(0, 2_007, 4_013, 6_020, 8_031, 10_036)
        val estimates = AccelerationEstimator().feed(times.map { t -> t to (5.0 + t / 1000.0).toFloat() })
        assertNull(estimates[1])
        for (a in estimates.drop(2)) assertEquals(1.0f, a!!.mps2, 0.01f)
    }

    @Test
    fun `no estimate until three fixes span two seconds`() {
        val estimator = AccelerationEstimator()
        assertNull(estimator.offer(0, 5f, 0.2f))
        assertNull(estimator.offer(1_000, 6f, 0.2f))
        assertNull(estimator.offer(1_500, 6.5f, 0.2f)) // three fixes, 1.5 s
        assertNotNull(estimator.offer(2_000, 7f, 0.2f))
    }

    @Test
    fun `a gap restarts the window`() {
        val estimator = AccelerationEstimator()
        estimator.feed(series(6) { 5.0 + it })
        assertNotNull(estimator.current)
        // 4 s without a usable fix (tunnel): the next fix starts over, nothing is differentiated across the gap.
        assertNull(estimator.offer(9_000, 20f, 0.2f))
        assertNull(estimator.offer(10_000, 20f, 0.2f))
        assertEquals(0f, estimator.offer(11_000, 20f, 0.2f)!!.mps2, 0.01f)
    }

    @Test
    fun `a fix that is not newer is ignored`() {
        val estimator = AccelerationEstimator()
        estimator.feed(series(5) { 5.0 + it })
        val before = estimator.current
        assertSame(before, estimator.offer(4_000, 50f, 0.2f))
        assertSame(before, estimator.offer(3_000, 0f, 0.2f))
    }

    @Test
    fun `a single speed spike is dropped`() {
        // +13 m/s within a second: more than any vehicle or runner can do.
        val fixes = series(12) { 10.0 }.map { (t, v) -> if (t == 6_000L) t to 23f else t to v }
        val estimates = AccelerationEstimator().feed(fixes).filterNotNull()
        assertTrue(estimates.all { abs(it.mps2) < 0.01f })
    }

    @Test
    fun `two impossible steps in a row start over from the new speed`() {
        val estimator = AccelerationEstimator()
        estimator.feed(series(5) { 10.0 })
        assertNotNull(estimator.offer(5_000, 40f, 0.2f)) // dropped as a spike, the previous estimate stays
        assertNull(estimator.offer(6_000, 40f, 0.2f)) // still impossible from 10 m/s: the window restarts with this fix
        estimator.offer(7_000, 40f, 0.2f)
        assertEquals(0f, estimator.offer(8_000, 40f, 0.2f)!!.mps2, 0.01f)
    }

    @Test
    fun `an inaccurate fix weighs less than accurate ones`() {
        val estimator = AccelerationEstimator()
        for (i in 0 until 4) estimator.offer(i * 1_000L, 10f, 0.1f)
        val a = estimator.offer(4_000, 11f, 1.0f)!!
        // Unweighted, the last fix would tilt the line by 0.2 m/s²; at 1/100 of the weight it barely moves it.
        assertTrue("a = ${a.mps2}", abs(a.mps2) < 0.02f)
    }

    @Test
    fun `a window of inaccurate fixes gives no estimate`() {
        val estimator = AccelerationEstimator()
        estimator.offer(0, 5f, 1.0f)
        estimator.offer(1_000, 5f, 1.0f)
        assertNull(estimator.offer(2_000, 5f, 1.0f)) // σ ≈ 0.71 m/s² > 0.5
    }

    @Test
    fun `direction changes with hysteresis`() {
        var state: AccelerationState = STEADY
        val seen = listOf(0.2f, 0.3f, 0.15f, 0.1f, -0.3f, -0.13f, 0f).map { a ->
            state = AccelerationEstimator.nextState(state, a)
            state
        }
        assertEquals(listOf(STEADY, ACCELERATING, ACCELERATING, STEADY, DECELERATING, DECELERATING, STEADY), seen)
    }

    @Test
    fun `values wobbling around the threshold do not flicker`() {
        var state: AccelerationState = STEADY
        var changes = 0
        for (a in listOf(0.27f, 0.2f, 0.3f, 0.16f, 0.26f, 0.14f, 0.24f, 0.18f)) {
            val next = AccelerationEstimator.nextState(state, a)
            if (next != state) changes++
            state = next
        }
        assertEquals(1, changes)
    }
}

class AccelerationHistoryTest {
    private fun AccelerationTrace.toList() = (0 until size).map { this[it] }

    @Test
    fun `keeps the last minute, oldest first`() {
        val history = AccelerationHistory()
        for (s in 0 until 75) history.record(s * 1_000L, s.toFloat())
        val trace = history.snapshot().toList()
        assertEquals(AccelerationTrace.SECONDS, trace.size)
        assertEquals(15f, trace.first(), 0f)
        assertEquals(74f, trace.last(), 0f)
    }

    @Test
    fun `missing seconds stay empty and the last value of a second wins`() {
        val history = AccelerationHistory()
        history.record(0, 1f)
        history.record(500, 2f)
        history.record(400, 9f) // an out-of-order fix within the same second still lands in it
        history.record(3_000, 3f)
        val trace = history.snapshot().toList()
        assertEquals(3f, trace[59], 0f)
        assertTrue(trace[58].isNaN() && trace[57].isNaN())
        assertEquals(9f, trace[56], 0f)
    }

    @Test
    fun `a fix without an estimate keeps the value of its second, a long gap clears the minute`() {
        val history = AccelerationHistory()
        history.record(0, 1f)
        history.record(400, null)
        assertEquals(1f, history.snapshot()[59], 0f)
        history.record(100_000, 5f)
        val trace = history.snapshot().toList()
        assertEquals(5f, trace.last(), 0f)
        assertTrue(trace.dropLast(1).all { it.isNaN() })
    }

    @Test
    fun `chart scale is at least 1 m per s2 and rounds up to a half`() {
        assertEquals(1f, AccelerationTrace.EMPTY.scaleMps2, 0f)
        val history = AccelerationHistory()
        history.record(0, 0.4f)
        assertEquals(1f, history.snapshot().scaleMps2, 0f)
        history.record(1_000, 1.3f)
        assertEquals(1.5f, history.snapshot().scaleMps2, 0f)
        history.record(2_000, -2.2f)
        assertEquals(2.5f, history.snapshot().scaleMps2, 0f)
    }
}
