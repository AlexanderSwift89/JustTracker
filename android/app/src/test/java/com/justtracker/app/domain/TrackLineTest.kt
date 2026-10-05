package com.justtracker.app.domain

import com.justtracker.app.domain.geo.Geo
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.stats.TrackStatsCalculator
import com.justtracker.app.domain.track.TrackLine
import com.justtracker.app.domain.track.TrackLineBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TrackLineTest {
    /** Reference values of a line, computed the batch way: the calculator's speeds, distances summed per segment. */
    private fun assertMatchesBatch(points: List<TrackPoint>, line: TrackLine) {
        assertEquals(points.size, line.size)
        val speeds = TrackStatsCalculator.smoothedSpeeds(points)
        var acc = 0.0
        for (i in points.indices) {
            val p = points[i]
            if (i > 0 && points[i - 1].segment == p.segment) acc += Geo.distanceMeters(points[i - 1].lat, points[i - 1].lon, p.lat, p.lon)
            assertEquals("speed $i", speeds[i], line.speedMps(i), 0f)
            assertEquals("distance $i", acc, line.distanceM(i), 0.0)
            assertEquals(p.lat, line.lat(i), 0.0)
            assertEquals(p.lon, line.lon(i), 0.0)
            assertEquals(p.timestamp, line.timestamp(i))
            assertEquals(p.altitudeM, line.altitudeM(i))
        }
    }

    private fun randomTrack(rnd: Random, n: Int): List<TrackPoint> {
        var segment = 0
        var t = 0L
        var lat = 55.0
        var lon = 37.0
        return List(n) { i ->
            if (i > 0 && rnd.nextInt(40) == 0) segment++ // a pause now and then
            t += if (rnd.nextInt(30) == 0) 0L else 1000L + rnd.nextLong(-200, 2000) // equal timestamps happen
            lat += rnd.nextDouble(-0.0002, 0.0002)
            lon += rnd.nextDouble(-0.0002, 0.0002)
            TrackPoint(
                trackId = 1, segment = segment, timestamp = t, lat = lat, lon = lon,
                altitudeM = if (rnd.nextBoolean()) 150.0 + rnd.nextDouble(-5.0, 5.0) else null,
                accuracyM = 5f, speedMps = if (rnd.nextInt(4) == 0) null else rnd.nextDouble(0.0, 8.0).toFloat(), bearingDeg = null,
            )
        }
    }

    @Test
    fun `empty line`() {
        val line = TrackLine.of(emptyList())
        assertTrue(line.isEmpty)
        assertEquals(0, line.segmentCount)
        assertNull(line.bounds)
        assertEquals(0.0, line.totalDistanceM, 0.0)
        assertEquals(0, line.indexForFraction(0.5f))
        assertNull(line.cursorAt(0, 0L))
    }

    @Test
    fun `speeds match the calculator and distance accumulates`() {
        val points = syntheticTrack(speedMps = 3.0, seconds = 20)
        val line = TrackLine.of(points)
        assertMatchesBatch(points, line)
        assertEquals(0.0, line.distanceM(0), 0.0)
        assertEquals(3.0 * 19, line.totalDistanceM, 0.5)
    }

    @Test
    fun `distance does not jump across a segment break`() {
        val a = syntheticTrack(speedMps = 2.0, seconds = 10, segment = 0)
        val b = syntheticTrack(speedMps = 2.0, seconds = 10, segment = 1, startTime = 60_000).map { it.copy(lat = it.lat + 0.1) }
        val line = TrackLine.of(a + b)
        // 9 intervals in each segment, the 10 km gap between them is ignored
        assertEquals(2.0 * 9, line.distanceM(9), 0.5)
        assertEquals(2.0 * 9, line.distanceM(10), 0.5)
        assertEquals(2.0 * 18, line.totalDistanceM, 0.5)
        assertEquals(2, line.segmentCount)
        assertEquals(10, line.segmentStart(1))
        assertEquals(10, line.segmentEnd(0))
        assertEquals(20, line.segmentEnd(1))
        assertEquals(0, line.segmentOf(9))
        assertEquals(1, line.segmentOf(10))
    }

    @Test
    fun `slow and fast sections keep their own speed`() {
        val slow = syntheticTrack(speedMps = 1.0, seconds = 30, segment = 0)
        val fast = syntheticTrack(speedMps = 10.0, seconds = 30, segment = 0, startTime = 30_000).map { it.copy(lat = it.lat + slow.last().lat) }
        val line = TrackLine.of(slow + fast)
        assertEquals(1.0, line.speedMps(10).toDouble(), 0.1)
        assertEquals(10.0, line.speedMps(50).toDouble(), 0.1)
    }

    @Test
    fun `appending in any portions gives the batch values, across chunk and segment boundaries`() {
        val rnd = Random(42)
        repeat(30) { round ->
            val points = randomTrack(rnd, rnd.nextInt(1, 200))
            val builder = TrackLineBuilder(chunkShift = 2) // 4 vertices per chunk
            var at = 0
            while (at < points.size) {
                val n = rnd.nextInt(1, 7).coerceAtMost(points.size - at)
                builder.append(points.subList(at, at + n))
                at += n
                assertMatchesBatch(points.subList(0, at), builder.snapshot())
            }
            assertEquals("round $round", points.size, builder.count)
        }
    }

    @Test
    fun `a snapshot never changes when the builder goes on`() {
        val rnd = Random(7)
        val points = randomTrack(rnd, 150)
        val builder = TrackLineBuilder(chunkShift = 2)
        val snapshots = mutableListOf<Pair<Int, TrackLine>>()
        for ((i, p) in points.withIndex()) {
            builder.append(p)
            if (i % 3 == 0) snapshots += (i + 1) to builder.snapshot()
        }
        for ((n, snapshot) in snapshots) assertMatchesBatch(points.subList(0, n), snapshot)
    }

    @Test
    fun `reset starts a new generation`() {
        val builder = TrackLineBuilder()
        builder.append(syntheticTrack(speedMps = 2.0, seconds = 5))
        val first = builder.snapshot()
        builder.reset()
        builder.append(syntheticTrack(speedMps = 4.0, seconds = 3))
        val second = builder.snapshot()
        assertTrue(second.generation != first.generation)
        assertEquals(3, second.size)
        assertEquals(5, first.size)
    }

    @Test
    fun `bounds enclose every vertex`() {
        val points = randomTrack(Random(3), 80)
        val box = TrackLine.of(points).bounds!!
        assertEquals(points.minOf { it.lat }, box.minLat, 0.0)
        assertEquals(points.maxOf { it.lat }, box.maxLat, 0.0)
        assertEquals(points.minOf { it.lon }, box.minLon, 0.0)
        assertEquals(points.maxOf { it.lon }, box.maxLon, 0.0)
    }

    @Test
    fun `taps resolve a vertex across segments`() {
        val a = syntheticTrack(speedMps = 2.0, seconds = 5, segment = 0)
        val b = syntheticTrack(speedMps = 4.0, seconds = 5, segment = 1, startTime = 30_000)
        val line = TrackLine.of(a + b)
        val tap = line.tapInfo(7, startedAt = 0L)
        assertNotNull(tap)
        assertEquals(32_000L, tap!!.elapsedMs)
        assertEquals(4.0, tap.speedMps.toDouble(), 0.1)
        assertEquals(2.0 * 4 + 4.0 * 2, tap.distanceFromStartM, 0.5)
        assertEquals(b[2].lat, tap.point.lat, 1e-9)
        assertNull(line.tapInfo(10, 0L))
        assertNull(line.tapInfo(-1, 0L))
    }

    @Test
    fun `cursor addresses vertices across segments and reports altitude`() {
        val a = syntheticTrack(speedMps = 2.0, seconds = 5, segment = 0, altitude = { 100.0 + it })
        val b = syntheticTrack(speedMps = 4.0, seconds = 5, segment = 1, startTime = 30_000)
        val line = TrackLine.of(a + b)
        assertEquals(10, line.size)
        assertEquals(2.0 * 4 + 4.0 * 4, line.totalDistanceM, 0.5)

        assertEquals(0f, line.cursorAt(0, startedAt = 0L)!!.fraction, 0f)
        assertEquals(103.0, line.cursorAt(3, 0L)!!.altitudeM!!, 1e-9)
        val c7 = line.cursorAt(7, startedAt = 0L)!!
        assertEquals(7, c7.index)
        assertEquals(32_000L, c7.elapsedMs)
        assertEquals(b[2].timestamp, c7.timestamp)
        assertNull(c7.altitudeM)
        assertEquals((8.0 + 8.0) / 24.0, c7.fraction.toDouble(), 0.02)
        assertEquals(1f, line.cursorAt(9, 0L)!!.fraction, 1e-6f)
        assertNull(line.cursorAt(10, 0L))
        assertNull(line.cursorAt(-1, 0L))
    }

    @Test
    fun `slider fraction maps to the nearest vertex by distance`() {
        val a = syntheticTrack(speedMps = 2.0, seconds = 5, segment = 0) // 0,2,4,6,8 m
        val b = syntheticTrack(speedMps = 4.0, seconds = 5, segment = 1, startTime = 30_000) // 8,12,16,20,24 m
        val line = TrackLine.of(a + b)
        assertEquals(0, line.indexForFraction(0f))
        assertEquals(9, line.indexForFraction(1f))
        assertEquals(2, line.indexForFraction(4f / 24f)) // exactly vertex 2
        assertEquals(1, line.indexForFraction(2.6f / 24f)) // 2.6 m → nearer to 2 m than 4 m
        assertEquals(7, line.indexForFraction(15f / 24f)) // 15 m → 16 m vertex in segment 1
        assertEquals(9, line.indexForFraction(2f)) // clamped
    }
}
