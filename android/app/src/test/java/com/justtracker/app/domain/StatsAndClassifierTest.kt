package com.justtracker.app.domain

import com.justtracker.app.domain.activity.ActivityClassifier
import com.justtracker.app.domain.geo.ElevationCalculator
import com.justtracker.app.domain.geo.ElevationResult
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.stats.IncrementalStats
import com.justtracker.app.domain.geo.Sample
import com.justtracker.app.domain.stats.TrackStatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Builds a straight northbound track at a constant speed; 1 point per second. */
internal fun syntheticTrack(
    speedMps: Double,
    seconds: Int,
    segment: Int = 0,
    startTime: Long = 0L,
    altitude: (Int) -> Double? = { null },
    jitter: Random? = null,
): List<TrackPoint> {
    val metersPerDegLat = 111_195.0
    return (0 until seconds).map { i ->
        val noise = jitter?.let { (it.nextDouble() - 0.5) * 0.4 } ?: 0.0
        TrackPoint(
            trackId = 1,
            segment = segment,
            timestamp = startTime + i * 1000L,
            lat = (speedMps * i + noise) / metersPerDegLat,
            lon = 0.0,
            altitudeM = altitude(i),
            accuracyM = 5f,
            speedMps = (speedMps + (jitter?.let { (it.nextDouble() - 0.5) * 0.3 } ?: 0.0)).toFloat(),
            bearingDeg = null,
        )
    }
}

class TrackStatsCalculatorTest {
    @Test
    fun `distance and moving time for constant speed`() {
        val points = syntheticTrack(speedMps = 2.0, seconds = 61)
        val stats = TrackStatsCalculator.calculate(points, pausedTimeMs = 0, startedAt = 0, finishedAt = 60_000)
        assertEquals(120.0, stats.distanceM, 0.5)
        assertEquals(60_000, stats.movingTimeMs)
        assertEquals(60_000, stats.totalTimeMs)
        assertEquals(2.0, stats.avgSpeedMps, 0.01)
        assertEquals(2.0, stats.maxSpeedMps, 0.01)
        assertEquals(61, stats.pointCount)
    }

    @Test
    fun `segments are not connected`() {
        val a = syntheticTrack(speedMps = 1.5, seconds = 30, segment = 0)
        // second segment starts 10 km away after a pause: the jump must not count
        val b = syntheticTrack(speedMps = 1.5, seconds = 30, segment = 1, startTime = 100_000).map { it.copy(lat = it.lat + 0.1) }
        val stats = TrackStatsCalculator.calculate(a + b, pausedTimeMs = 70_000, startedAt = 0, finishedAt = 129_000)
        assertEquals(1.5 * 29 * 2, stats.distanceM, 0.5)
        assertEquals(58_000, stats.movingTimeMs)
        assertEquals(59_000, stats.totalTimeMs)
    }

    @Test
    fun `standing still does not accumulate moving time`() {
        val points = syntheticTrack(speedMps = 0.0, seconds = 20).mapIndexed { i, p -> p.copy(timestamp = i * 30_000L) }
        val stats = TrackStatsCalculator.calculate(points, 0, 0, points.last().timestamp)
        assertEquals(0, stats.movingTimeMs)
        assertEquals(0.0, stats.avgSpeedMps, 0.0)
    }

    @Test
    fun `single speed spike is suppressed by median`() {
        val points = syntheticTrack(speedMps = 1.0, seconds = 20).toMutableList()
        points[10] = points[10].copy(speedMps = 40f)
        val stats = TrackStatsCalculator.calculate(points, 0, 0, 19_000)
        assertTrue("max speed should ignore spike, got ${stats.maxSpeedMps}", stats.maxSpeedMps < 2.0)
    }

    @Test
    fun `empty list gives empty stats`() {
        val stats = TrackStatsCalculator.calculate(emptyList(), 0, 0, null)
        assertEquals(0, stats.pointCount)
        assertEquals(0.0, stats.distanceM, 0.0)
    }

    @Test
    fun `elevation is counted per segment`() {
        // climb 20 m, pause, resume 300 m higher (driven there), climb 20 m again
        val a = syntheticTrack(speedMps = 1.4, seconds = 600, segment = 0, altitude = { 100.0 + it * 20.0 / 600 })
        val b = syntheticTrack(speedMps = 1.4, seconds = 600, segment = 1, startTime = 1_200_000, altitude = { 400.0 + it * 20.0 / 600 })
        val stats = TrackStatsCalculator.calculate(a + b, pausedTimeMs = 600_000, startedAt = 0, finishedAt = 1_800_000)
        assertEquals(40.0, stats.elevationGainM, 5.0)
        assertEquals(0.0, stats.elevationLossM, 0.0)
    }
}

class IncrementalStatsTest {
    @Test
    fun `incremental totals match full recalculation`() {
        val points = syntheticTrack(speedMps = 3.0, seconds = 50, jitter = Random(1))
        val inc = IncrementalStats()
        var prev: TrackPoint? = null
        for (p in points) {
            val d = if (prev == null) 0.0 else com.justtracker.app.domain.geo.Geo.distanceMeters(prev.lat, prev.lon, p.lat, p.lon)
            inc.accept(Sample(p.timestamp, p.lat, p.lon, p.accuracyM, p.speedMps), d, p.speedMps!!)
            prev = p
        }
        val full = TrackStatsCalculator.calculate(points, 0, 0, points.last().timestamp)
        assertEquals(full.distanceM, inc.distanceM, 0.01)
        assertEquals(full.movingTimeMs, inc.movingTimeMs)
        assertEquals(50, inc.pointCount)
    }

    @Test
    fun `breakSegment prevents distance across pause`() {
        val inc = IncrementalStats()
        inc.accept(Sample(0, 0.0, 0.0, 5f, 1f), 0.0, 1f)
        inc.accept(Sample(1000, 0.00001, 0.0, 5f, 1f), 1.1, 1f)
        inc.breakSegment()
        inc.accept(Sample(60_000, 0.01, 0.0, 5f, 1f), 1000.0, 1f) // distance arg ignored for first point of segment
        assertEquals(1.1, inc.distanceM, 0.001)
    }
}

class ElevationCalculatorTest {
    /** Stationary AR(1) noise with the given sigma and correlation time — how phone GPS altitude errors behave. */
    private fun gpsNoise(seconds: Int, sigmaM: Double, correlationS: Double, seed: Int): DoubleArray {
        val rnd = Random(seed)
        val a = exp(-1.0 / correlationS)
        val out = DoubleArray(seconds)
        var e = 0.0
        for (i in 0 until seconds) {
            // Box–Muller
            val g = sqrt(-2.0 * ln(1.0 - rnd.nextDouble())) * cos(2 * PI * rnd.nextDouble())
            e = a * e + sqrt(1 - a * a) * sigmaM * g
            out[i] = e
        }
        return out
    }

    /** The pre-1.0.2 algorithm (5-point moving average, 3 m stair-step hysteresis) for comparison. */
    private fun oldGain(points: List<TrackPoint>): Double {
        val h = points.mapNotNull { it.altitudeM }
        val smoothed = h.indices.map { i -> h.subList(maxOf(0, i - 2), minOf(h.size, i + 3)).average() }
        var gain = 0.0
        var ref = smoothed.first()
        for (x in smoothed.drop(1)) {
            if (x - ref >= 3.0) { gain += x - ref; ref = x } else if (x - ref <= -3.0) ref = x
        }
        return gain
    }

    @Test
    fun `steady climb is counted`() {
        // 2 km walk, +5 %: 100 m of climbing
        val points = syntheticTrack(speedMps = 1.4, seconds = 1430, altitude = { 100.0 + 1.4 * it * 0.05 })
        val r = ElevationCalculator.gainLoss(points)
        assertEquals(97.0, r.gainM, 4.0)
        assertEquals(0.0, r.lossM, 0.0)
    }

    @Test
    fun `steady climb with GPS noise is still counted`() {
        val noise = gpsNoise(1430, sigmaM = 3.0, correlationS = 30.0, seed = 4)
        val points = syntheticTrack(speedMps = 1.4, seconds = 1430, altitude = { 100.0 + 1.4 * it * 0.05 + noise[it] })
        val r = ElevationCalculator.gainLoss(points)
        assertTrue("gain ${r.gainM}", r.gainM in 85.0..112.0)
        assertTrue("loss ${r.lossM}", r.lossM < 15.0)
    }

    @Test
    fun `hill up and down gives gain and loss`() {
        // 1 km up 30 m, 1 km down 30 m, walking
        val points = syntheticTrack(speedMps = 1.4, seconds = 1430, altitude = { i -> val d = 1.4 * i; if (d < 1000) 100 + d * 0.03 else 130 - (d - 1000) * 0.03 })
        val r = ElevationCalculator.gainLoss(points)
        assertEquals(30.0, r.gainM, 4.0)
        assertEquals(30.0, r.lossM, 4.0)
    }

    @Test
    fun `GPS noise on a flat run is not climbing`() {
        // 10 km flat run with realistic phone altitude noise: the old algorithm reported 150+ m here.
        val noise = gpsNoise(4000, sigmaM = 3.0, correlationS = 30.0, seed = 1)
        val points = syntheticTrack(speedMps = 2.5, seconds = 4000, altitude = { 150.0 + noise[it] })
        val r = ElevationCalculator.gainLoss(points)
        val old = oldGain(points)
        assertTrue("gain ${r.gainM} (old $old)", r.gainM < 0.4 * old && r.gainM < 80.0)
        assertTrue("loss ${r.lossM}", r.lossM < 80.0)
    }

    @Test
    fun `short altitude oscillation is averaged away`() {
        // ±4 m with a 100 m wavelength: below what GPS can resolve, not terrain
        val points = syntheticTrack(speedMps = 2.5, seconds = 2000, altitude = { 150.0 + 4 * sin(2 * PI * it / 40) })
        val r = ElevationCalculator.gainLoss(points)
        assertEquals(0.0, r.gainM, 0.0)
        assertEquals(0.0, r.lossM, 0.0)
    }

    @Test
    fun `standing still with a wandering altitude is not climbing`() {
        val noise = gpsNoise(1800, sigmaM = 5.0, correlationS = 60.0, seed = 11)
        val points = syntheticTrack(speedMps = 0.0, seconds = 1800, altitude = { 150.0 + noise[it] })
        val r = ElevationCalculator.gainLoss(points)
        assertEquals(0.0, r.gainM, 0.0)
        assertEquals(0.0, r.lossM, 0.0)
    }

    @Test
    fun `stuck altitude excursions are removed`() {
        // The pattern of the 1.0.1 report (flat run, "+134 m / -125 m"): the altitude jumps 12–20 m within a
        // second, stays there for 20–160 s and jumps back.
        val excursions = listOf(Triple(500, 590, 20.0), Triple(1500, 1520, 13.5), Triple(2500, 2660, 11.7))
        val points = syntheticTrack(speedMps = 2.5, seconds = 4000, altitude = { i ->
            150.0 + (excursions.firstOrNull { (from, to, _) -> i in from until to }?.third ?: 0.0)
        })
        val r = ElevationCalculator.gainLoss(points)
        assertEquals(0.0, r.gainM, 0.0)
        assertEquals(0.0, r.lossM, 0.0)
    }

    @Test
    fun `step that does not return is a real change`() {
        // A slowly updated altitude catching up once: +10 m and it stays there
        val points = syntheticTrack(speedMps = 1.4, seconds = 1200, altitude = { if (it < 600) 150.0 else 160.0 })
        val r = ElevationCalculator.gainLoss(points)
        assertEquals(10.0, r.gainM, 1.0)
        assertEquals(0.0, r.lossM, 0.0)
    }

    @Test
    fun `height difference across a pause is not counted`() {
        val before = syntheticTrack(speedMps = 1.4, seconds = 300, segment = 0, altitude = { 150.0 })
        val after = syntheticTrack(speedMps = 1.4, seconds = 300, segment = 1, startTime = 900_000, altitude = { 400.0 })
        val r = ElevationCalculator.gainLoss(before + after)
        assertEquals(0.0, r.gainM, 0.0)
        assertEquals(0.0, r.lossM, 0.0)
    }

    @Test
    fun `altitudes with poor vertical accuracy are ignored`() {
        // 700 s of +30 m reported with a 40 m vertical accuracy — too long to be a spike, only the gate removes it
        val bad = 300 until 1000
        val points = syntheticTrack(speedMps = 1.4, seconds = 2000, altitude = { if (it in bad) 180.0 else 150.0 })
            .mapIndexed { i, p -> p.copy(verticalAccuracyM = if (i in bad) 40f else 4f) }
        val r = ElevationCalculator.gainLoss(points)
        assertEquals(0.0, r.gainM, 0.0)
        assertEquals(0.0, r.lossM, 0.0)
        // the same data without accuracy information is taken at face value
        val unknown = ElevationCalculator.gainLoss(points.map { it.copy(verticalAccuracyM = null) })
        assertEquals(30.0, unknown.gainM, 1.0)
    }

    @Test
    fun `uniformly poor vertical accuracy still gives a result`() {
        val points = syntheticTrack(speedMps = 1.4, seconds = 1430, altitude = { 100.0 + 1.4 * it * 0.05 })
            .map { it.copy(verticalAccuracyM = 25f) }
        assertEquals(97.0, ElevationCalculator.gainLoss(points).gainM, 4.0)
    }

    @Test
    fun `points without altitude are skipped`() {
        val points = syntheticTrack(speedMps = 1.4, seconds = 1430, altitude = { if (it % 3 == 0) null else 100.0 + 1.4 * it * 0.05 })
        assertEquals(97.0, ElevationCalculator.gainLoss(points).gainM, 4.0)
        assertEquals(ElevationResult.ZERO, ElevationCalculator.gainLoss(syntheticTrack(speedMps = 1.4, seconds = 100)))
        assertEquals(ElevationResult.ZERO, ElevationCalculator.gainLoss(emptyList()))
    }

    @Test
    fun `turning points count whole legs beyond the threshold`() {
        fun tp(vararg v: Double) = ElevationCalculator.turningPointGainLoss(v, 6.0)
        assertEquals(ElevationResult(0.0, 0.0), tp(0.0, 3.0, 0.0, 3.0, 0.0))
        assertEquals(ElevationResult(20.0, 8.0), tp(0.0, 10.0, 2.0, 12.0))
        // the last leg counts on its own, a sub-threshold drop after the top does not
        assertEquals(ElevationResult(10.0, 0.0), tp(0.0, 10.0, 5.0))
        // an initial dip below the threshold does not shorten the climb that follows
        assertEquals(ElevationResult(13.0, 0.0), tp(100.0, 97.0, 104.0, 110.0))
    }
}

class ActivityClassifierTest {
    private fun speeds(base: Double, n: Int = 100, spread: Double = 0.2, seed: Int = 1): List<Float> {
        val rnd = Random(seed)
        return List(n) { (base + (rnd.nextDouble() - 0.5) * 2 * spread * base).toFloat() }
    }

    @Test
    fun `walking around 1_4 mps`() = assertEquals(ActivityType.WALK, ActivityClassifier.classify(speeds(1.4)))

    @Test
    fun `running around 3 mps`() = assertEquals(ActivityType.RUN, ActivityClassifier.classify(speeds(3.0)))

    @Test
    fun `cycling around 6 mps`() = assertEquals(ActivityType.BIKE, ActivityClassifier.classify(speeds(6.0)))

    @Test
    fun `driving around 15 mps`() = assertEquals(ActivityType.CAR, ActivityClassifier.classify(speeds(15.0)))

    @Test
    fun `city driving with many stops still CAR via v90`() {
        val s = speeds(4.0, n = 80) + speeds(20.0, n = 20)
        assertEquals(ActivityType.CAR, ActivityClassifier.classify(s))
    }

    @Test
    fun `too few moving points is UNKNOWN`() {
        assertEquals(ActivityType.UNKNOWN, ActivityClassifier.classify(speeds(1.4, n = 5)))
        assertEquals(ActivityType.UNKNOWN, ActivityClassifier.classify(List(50) { 0.1f }))
    }

    @Test
    fun `percentile nearest rank`() {
        val sorted = listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f)
        assertEquals(5.0, ActivityClassifier.percentile(sorted, 0.5), 0.0)
        assertEquals(9.0, ActivityClassifier.percentile(sorted, 0.9), 0.0)
        assertEquals(10.0, ActivityClassifier.percentile(sorted, 1.0), 0.0)
    }
}
