package io.treklog.app.domain

import io.treklog.app.domain.activity.ActivityClassifier
import io.treklog.app.domain.geo.ElevationCalculator
import io.treklog.app.domain.model.ActivityType
import io.treklog.app.domain.model.TrackPoint
import io.treklog.app.domain.stats.IncrementalStats
import io.treklog.app.domain.geo.Sample
import io.treklog.app.domain.stats.TrackStatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
}

class IncrementalStatsTest {
    @Test
    fun `incremental totals match full recalculation`() {
        val points = syntheticTrack(speedMps = 3.0, seconds = 50, jitter = Random(1))
        val inc = IncrementalStats()
        var prev: TrackPoint? = null
        for (p in points) {
            val d = if (prev == null) 0.0 else io.treklog.app.domain.geo.Geo.distanceMeters(prev.lat, prev.lon, p.lat, p.lon)
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
    @Test
    fun `steady climb counted, jitter ignored`() {
        val climb = (0 until 100).map { 100.0 + it * 0.5 } // +49.5 m
        val r = ElevationCalculator.gainLoss(climb)
        assertEquals(49.5, r.gainM, 4.0)
        assertEquals(0.0, r.lossM, 0.0)
    }

    @Test
    fun `noise below threshold yields no gain`() {
        val rnd = Random(7)
        val flat = (0 until 200).map { 100.0 + (rnd.nextDouble() - 0.5) * 2.0 }
        val r = ElevationCalculator.gainLoss(flat)
        assertEquals(0.0, r.gainM, 0.0)
        assertEquals(0.0, r.lossM, 0.0)
    }

    @Test
    fun `up then down gives gain and loss`() {
        val profile = (0 until 50).map { 100.0 + it } + (0 until 50).map { 149.0 - it }
        val r = ElevationCalculator.gainLoss(profile)
        assertEquals(49.0, r.gainM, 4.0)
        assertEquals(49.0, r.lossM, 4.0)
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
