package com.justtracker.app.domain

import com.justtracker.app.domain.geo.Geo
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.stats.AccelerationState.ACCELERATING
import com.justtracker.app.domain.stats.AccelerationState.DECELERATING
import com.justtracker.app.domain.track.TrackAcceleration
import com.justtracker.app.domain.track.TrackLine
import com.justtracker.app.testing.Recording
import com.justtracker.app.testing.Reported
import com.justtracker.app.testing.recordDrive
import com.justtracker.app.testing.stopAndGo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TrackAccelerationTest {
    private fun Recording.acceleration(): TrackAcceleration = TrackAcceleration.of(points, TrackLine.of(points))

    /** Index of the stored point at [second] after the start (the fixtures start at 1 000 000 ms). */
    private fun Recording.at(second: Double): Int = points.indexOfFirst { it.timestamp >= 1_000_000L + (second * 1000).toLong() }

    private fun TrackAcceleration.estimated(): List<Float> = (0 until size).map { mps2(it) }.filter { !it.isNaN() }

    @Test
    fun `history is the live value half a window earlier, from the same fixes`() {
        val rec = recordDrive(seconds = 70, noise = 0.1) { t ->
            when {
                t < 10 -> 5.0
                t < 20 -> 5.0 + 2.0 * (t - 10)
                t < 40 -> 25.0
                t < 50 -> 25.0 - 2.0 * (t - 40)
                else -> 5.0
            }
        }
        // Every fix is stored at these speeds, so point k is fix k.
        assertEquals(rec.fixTimes.size, rec.points.size)
        val acc = rec.acceleration()
        var compared = 0
        for (k in 0..rec.points.size - 3) {
            val live = rec.live[k + 2]
            val history = acc.mps2(k)
            if (live == null) {
                assertTrue("vertex $k", history.isNaN())
                continue
            }
            assertEquals("vertex $k", live.mps2, history, 1e-4f)
            assertEquals("vertex $k", live.state, acc.state(k))
            compared++
        }
        assertTrue(compared > 60)
    }

    @Test
    fun `a ramp shows where it starts, not two seconds later`() {
        val rec = recordDrive(seconds = 80) { t -> if (t < 60) 10.0 else 10.0 + 2.0 * (t - 60) }
        val acc = rec.acceleration()
        assertEquals(1.0f, acc.mps2(rec.at(60.0)), 0.15f)
        for (s in 63..77) assertEquals("t=$s", 2.0f, acc.mps2(rec.at(s.toDouble())), 0.15f)
        // The live value reaches half the ramp only later.
        val liveHalf = rec.live.indexOfFirst { it != null && it.mps2 >= 1f }
        assertTrue("live half at ${liveHalf}s", liveHalf >= 61.5)
    }

    @Test
    fun `the emulator's stop and go gives one start from rest and one stop`() {
        val rec = stopAndGo()
        val acc = rec.acceleration()
        assertTrue(acc.isAvailable)
        assertEquals(2, acc.episodes.size)
        val (up, down) = acc.episodes
        assertEquals(ACCELERATING, up.kind)
        assertTrue(up.fromRest)
        assertEquals(0f, up.fromSpeedMps, 0f)
        assertEquals(20f, up.toSpeedMps, 0.3f)
        assertEquals(10_000.0, up.durationMs.toDouble(), 1_000.0)
        assertEquals(2.0f, up.peakMps2, 0.15f)
        assertEquals(DECELERATING, down.kind)
        assertTrue(down.toRest)
        assertEquals(20f, down.fromSpeedMps, 0.3f)
        assertEquals(0f, down.toSpeedMps, 0f)
        assertEquals(6_900.0, down.durationMs.toDouble(), 1_000.0)
        assertEquals(-2.9f, down.peakMps2, 0.2f)
        assertSame(up, acc.maxAcceleration)
        assertSame(down, acc.maxDeceleration)
        // The start goes to where the car stood.
        assertEquals(0.0, up.startDistanceM, 1e-9)
        // Standing still has no estimate: its points are 30 s apart or not stored at all.
        assertTrue(acc.mps2(0).isNaN())
    }

    @Test
    fun `the stop-and-go recorded on the emulator ends at a standstill too`() {
        // Stored points of TC-147 (API 34, fused provider: smoothed speeds, accuracy 0.5 m/s), 30 s of standing at the end.
        fun p(t: Long, lat: Double, v: Float) = TrackPoint(
            trackId = 1, segment = 0, timestamp = t, lat = lat, lon = 37.6199983, altitudeM = 150.0, accuracyM = 5f, speedMps = v,
            bearingDeg = null, speedAccuracyMps = 0.5f,
        )
        val points = listOf(
            p(0, 55.7599983, 0.0f), p(18223, 55.7600476, 3.466144f), p(19283, 55.7601009, 5.437324f),
            p(20329, 55.7601724, 7.427564f), p(21389, 55.7602627, 9.430217f), p(22444, 55.7603713, 11.430347f),
            p(23505, 55.7604972, 13.428694f), p(24574, 55.7606424, 15.431631f), p(25637, 55.7608035, 17.431349f),
            p(26697, 55.7609833, 19.430971f), p(27763, 55.7611677, 19.874371f), p(28827, 55.7613498, 19.971777f),
            p(29894, 55.7615307, 19.992973f), p(30964, 55.7617111, 19.997480f), p(32016, 55.7618892, 19.998600f),
            p(33023, 55.7619364, 19.975931f), p(34024, 55.7620793, 19.988766f), p(35025, 55.762249, 19.996700f),
            p(36264, 55.7625721, 20.017580f), p(37323, 55.762779, 20.008135f), p(38383, 55.7629651, 20.002153f),
            p(39448, 55.7631469, 19.999928f), p(40513, 55.7633066, 17.663029f), p(41575, 55.7634344, 14.806805f),
            p(42636, 55.7635332, 11.839733f), p(43692, 55.7636045, 8.850333f), p(44742, 55.7636488, 5.856818f),
            p(75050, 55.763655, 0f),
        )
        val acc = TrackAcceleration.of(points, TrackLine.of(points))
        assertEquals(2, acc.episodes.size)
        val (up, down) = acc.episodes
        assertTrue(up.fromRest)
        assertEquals(20f, up.toSpeedMps, 0.6f)
        assertEquals(10_000.0, up.durationMs.toDouble(), 1_000.0)
        assertTrue(down.toRest)
        assertEquals(0f, down.toSpeedMps, 0f)
        assertEquals(points.lastIndex, down.endIndex)
        assertEquals(6_900.0, down.durationMs.toDouble(), 1_000.0)
        assertEquals(-2.8f, down.peakMps2, 0.3f)
    }

    @Test
    fun `a stop at a traffic light too short for a standing point still ends and starts at 0`() {
        // 15 m/s, braking at 2.5 m/s² to a stop, 20 s standing (no standing point is stored), +2 m/s² back to 15 m/s.
        val rec = recordDrive(seconds = 100, noise = 0.1) { t ->
            when {
                t < 30 -> 15.0
                t < 36 -> 15.0 - 2.5 * (t - 30)
                t < 56 -> 0.0
                t < 63.5 -> 2.0 * (t - 56)
                else -> 15.0
            }
        }
        assertTrue(rec.points.none { it.timestamp in 1_037_000L..1_055_000L })
        val acc = rec.acceleration()
        assertEquals(2, acc.episodes.size)
        val (down, up) = acc.episodes
        assertEquals(DECELERATING, down.kind)
        assertTrue(down.toRest)
        assertEquals(0f, down.toSpeedMps, 0f)
        assertEquals(6_000.0, down.durationMs.toDouble(), 1_000.0)
        assertEquals(ACCELERATING, up.kind)
        assertTrue(up.fromRest)
        assertEquals(0f, up.fromSpeedMps, 0f)
        assertEquals(7_500.0, up.durationMs.toDouble(), 1_000.0)
    }

    @Test
    fun `a steady speed with GPS noise stays steady`() {
        val acc = recordDrive(seconds = 600, noise = 0.35, seed = 3) { 10.0 }.acceleration()
        val values = acc.estimated()
        assertTrue(values.size > 550)
        assertTrue(values.count { abs(it) < 0.25f } >= values.size * 0.95)
        assertTrue(acc.episodes.isEmpty())
        assertEquals(1f, acc.scaleMps2, 0f)
        assertNull(acc.maxAcceleration)
    }

    @Test
    fun `a walk thinned to a point every two seconds is estimated but has no episodes`() {
        val rec = recordDrive(seconds = 300, noise = 0.2, seed = 5) { t -> if (t in 100.0..120.0 || t in 200.0..220.0) 0.0 else 1.4 }
        val acc = rec.acceleration()
        assertTrue("stored ${rec.points.size}", rec.points.size < 200)
        val moving = rec.points.indices.filter { rec.points[it].speedMps!! > 1f }
        assertTrue(moving.count { !acc.mps2(it).isNaN() } >= moving.size / 2)
        assertTrue(acc.isAvailable)
        assertTrue(acc.episodes.isEmpty())
    }

    @Test
    fun `a receiver that reports 0 while moving or no speed at all gives no acceleration`() {
        for (reported in listOf(Reported.ZERO, Reported.NONE)) {
            val acc = recordDrive(seconds = 60, reported = reported) { t -> 5.0 + 0.2 * t }.acceleration()
            assertFalse(reported.name, acc.isAvailable)
            assertTrue(reported.name, acc.estimated().isEmpty())
            assertTrue(reported.name, acc.episodes.isEmpty())
        }
    }

    @Test
    fun `after a contradiction the receiver's speed is not trusted for 10 s`() {
        // The receiver claims a standstill for 3 s while driving at 10 m/s.
        val rec = recordDrive(seconds = 120, speedOverride = { t -> if (t in 60.0..62.0) 0f else null }) { 10.0 }
        val acc = rec.acceleration()
        for (s in 61..72) assertTrue("t=$s", acc.mps2(rec.at(s.toDouble())).isNaN())
        for (s in 77..115) assertFalse("t=$s", acc.mps2(rec.at(s.toDouble())).isNaN())
    }

    @Test
    fun `a segment start without a reported speed is no start from 0`() {
        val rec = recordDrive(seconds = 60, speedMissing = { t -> t == 0.0 }) { 10.0 }
        assertEquals(0f, rec.points.first().speedMps!!, 0f)
        assertNull(rec.points.first().speedAccuracyMps)
        assertTrue(rec.acceleration().episodes.isEmpty())
    }

    @Test
    fun `no episode across a tunnel`() {
        // No fixes for 10 s, during which the car went from 10 to 20 m/s.
        val rec = recordDrive(seconds = 120, gap = { t -> t > 60 && t < 70 }) { t -> if (t < 62) 10.0 else if (t < 67) 10.0 + 2.0 * (t - 62) else 20.0 }
        val acc = rec.acceleration()
        assertTrue(acc.episodes.isEmpty())
        assertFalse(acc.mps2(rec.at(55.0)).isNaN())
        assertFalse(acc.mps2(rec.at(80.0)).isNaN())
    }

    @Test
    fun `a single speed spike is dropped, two in a row start anew`() {
        val single = recordDrive(seconds = 60, speedOverride = { t -> if (t == 30.0) 40f else null }) { 10.0 }
        val one = single.acceleration()
        assertTrue(one.estimated().all { abs(it) < 0.25f })
        assertTrue(one.episodes.isEmpty())
        // Both 30 m/s off: a second impossible step against the last kept speed (15 m/s² over 2 s) starts a new series.
        val double = recordDrive(seconds = 60, speedOverride = { t -> if (t == 30.0 || t == 31.0) 40f else null }) { 10.0 }
        val two = double.acceleration()
        for (s in 30..32) assertTrue("t=$s", two.mps2(double.at(s.toDouble())).isNaN())
        assertTrue(two.estimated().all { abs(it) < 0.25f })
    }

    @Test
    fun `a poor speed accuracy is left out and accuracies weigh the window`() {
        // One fix 2 m/s off: with its accuracy 1.2 it is ignored, with 0.9 it weighs little, without accuracies it weighs as the rest.
        fun at30(sigma: Float?, outlierSigma: Float?): Float {
            val rec = recordDrive(
                seconds = 60,
                sigma = sigma,
                positionNoise = 0.3,
                speedOverride = { t -> if (t == 30.0) 12f else null },
                sigmaOverride = { t -> if (t == 30.0) outlierSigma else null },
            ) { 10.0 }
            return rec.acceleration().mps2(rec.at(29.0))
        }
        assertEquals(0f, at30(0.1f, 1.2f), 1e-4f)
        val weighted = abs(at30(0.1f, 0.9f))
        val equal = abs(at30(null, null))
        assertTrue("weighted $weighted, equal $equal", weighted < equal / 10)
    }

    @Test
    fun `an accuracy of 0,3 and no accuracy give the same result`() {
        val profile = { t: Double -> if (t < 20) 8.0 else if (t < 30) 8.0 + 1.5 * (t - 20) else 23.0 }
        val with = recordDrive(seconds = 60, sigma = 0.3f, noise = 0.2, positionNoise = 0.3, speedAt = profile).acceleration()
        val without = recordDrive(seconds = 60, sigma = null, noise = 0.2, positionNoise = 0.3, speedAt = profile).acceleration()
        assertEquals(with.size, without.size)
        for (k in 0 until with.size) assertEquals("vertex $k", with.mps2(k), without.mps2(k), 0f)
        assertEquals(with.episodes, without.episodes)
    }

    @Test
    fun `episodes need a real change of speed, long enough and strong enough`() {
        fun episodes(top: Double, from: Double, rate: Double, at: Double = 30.0) =
            recordDrive(seconds = 90) { t -> if (t < at) from else minOf(from + rate * (t - at), maxOf(top, from)) }.acceleration().episodes
        // Walking pace: +1.2 m/s in 2 s is below the 1.5 m/s floor.
        assertTrue(episodes(top = 3.0, from = 1.8, rate = 0.6).isEmpty())
        // A slow drift: 0.4 m/s² never reaches the 0.5 m/s² peak.
        assertTrue(episodes(top = 14.0, from = 10.0, rate = 0.4).isEmpty())
        // Driving with a 30 m/s maximum later on: a change needs 3 m/s.
        fun drive(dv: Double) = recordDrive(seconds = 150) { t ->
            when {
                t < 30 -> 20.0
                t < 30 + dv -> 20.0 + (t - 30)
                t < 80 -> 20.0 + dv
                t < 90 -> 20.0 + dv + (30.0 - 20.0 - dv) * (t - 80) / 10.0
                else -> 30.0
            }
        }.acceleration().episodes.filter { it.startIndex < 60 }
        assertTrue(drive(2.5).isEmpty())
        assertEquals(1, drive(3.5).size)
    }

    @Test
    fun `the colour scale is the 95th percentile, rounded up to 0,5 within 1 to 6`() {
        val hard = recordDrive(seconds = 200) { t -> val c = t % 20; if (c < 5) 10.0 + 3.0 * c else if (c < 10) 25.0 - 3.0 * (c - 5) else 10.0 }
        val acc = hard.acceleration()
        assertTrue(acc.scaleMps2 in 2.5f..3.5f)
        assertEquals(0f, acc.scaleMps2 * 2 % 1, 0f)
        assertTrue(acc.episodes.size >= 15)
        assertEquals(6, acc.strongest().size)
        assertTrue(acc.strongest().zipWithNext().all { (a, b) -> abs(a.peakMps2) >= abs(b.peakMps2) })
        assertEquals(3, acc.strongest().count { it.kind == ACCELERATING })
        assertTrue(acc.boundaries.toList() == acc.boundaries.sorted())
        assertTrue(acc.boundaries.all { it in 0 until acc.size })
    }

    @Test
    fun `the cursor gets the acceleration where there is one`() {
        val rec = recordDrive(seconds = 60) { t -> if (t < 30) 10.0 else 10.0 + (t - 30) }
        val acc = rec.acceleration()
        val line = TrackLine.of(rec.points)
        val moving = acc.annotate(line.cursorAt(rec.at(40.0), 1_000_000L)!!)
        assertEquals(1f, moving.accelerationMps2!!, 0.15f)
        assertEquals(ACCELERATING, moving.accelerationState)
        // The segment's first point: its speed alone starts no window.
        val first = acc.annotate(line.cursorAt(0, 1_000_000L)!!)
        assertEquals(acc.mps2(0).takeUnless { it.isNaN() }, first.accelerationMps2)
    }

    @Test
    fun `too few points or a different line give nothing`() {
        val points = recordDrive(seconds = 2) { 10.0 }.points
        assertSame(TrackAcceleration.NONE, TrackAcceleration.of(points.take(2), TrackLine.of(points.take(2))))
        assertSame(TrackAcceleration.NONE, TrackAcceleration.of(points, TrackLine.EMPTY))
        assertFalse(TrackAcceleration.NONE.isAvailable)
        assertNull(TrackAcceleration.NONE.state(0))
    }

    @Test
    fun `100 000 points are computed in linear time`() {
        val points = ArrayList<TrackPoint>(100_000)
        var north = 0.0
        for (i in 0 until 100_000) {
            val v = 15.0 + 5.0 * kotlin.math.sin(i / 30.0)
            north += v
            points += TrackPoint(
                trackId = 1, segment = i / 20_000, timestamp = i * 1000L, lat = 55.0 + north / Geo.METERS_PER_DEGREE, lon = 37.0,
                altitudeM = null, accuracyM = 5f, speedMps = v.toFloat(), bearingDeg = null, speedAccuracyMps = 0.3f,
            )
        }
        val line = TrackLine.of(points)
        val started = System.nanoTime()
        val acc = TrackAcceleration.of(points, line)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("$ms ms", ms < 2_000)
        assertTrue(acc.isAvailable)
    }
}
