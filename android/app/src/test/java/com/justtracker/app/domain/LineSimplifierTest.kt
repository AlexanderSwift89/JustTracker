package com.justtracker.app.domain

import com.justtracker.app.domain.geo.Geo
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.track.LineSimplifier
import com.justtracker.app.domain.track.TrackLine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LineSimplifierTest {
    private fun walk(rnd: Random, n: Int): TrackLine {
        var lat = 55.0
        var lon = 37.0
        var heading = 0.0
        return TrackLine.of(List(n) { i ->
            heading += rnd.nextDouble(-0.3, 0.3)
            lat += 2e-5 * kotlin.math.cos(heading)
            lon += 3e-5 * kotlin.math.sin(heading)
            TrackPoint(trackId = 1, segment = 0, timestamp = i * 1000L, lat = lat, lon = lon, altitudeM = null,
                accuracyM = 5f, speedMps = rnd.nextDouble(0.0, 5.0).toFloat(), bearingDeg = null)
        })
    }

    @Test
    fun `every dropped vertex stays within the tolerance of the kept line`() {
        val rnd = Random(5)
        repeat(20) {
            val line = walk(rnd, rnd.nextInt(3, 600))
            for (tol in doubleArrayOf(1.0, 4.0, 16.0, 64.0)) {
                val s = LineSimplifier.simplify(line, 0, line.size - 1, tol)
                assertEquals(0, s.kept.first())
                assertEquals(line.size - 1, s.kept.last())
                for (k in 1 until s.kept.size) assertTrue(s.kept[k] > s.kept[k - 1])
                for (seg in 0 until s.kept.size - 1) {
                    val a = s.kept[seg]
                    val b = s.kept[seg + 1]
                    for (v in a..b) {
                        val d = Geo.distanceToSegmentMeters(line.lat(v), line.lon(v), line.lat(a), line.lon(a), line.lat(b), line.lon(b))
                        assertTrue("vertex $v is $d m off at tolerance $tol", d <= tol + 1e-6)
                    }
                }
            }
        }
    }

    @Test
    fun `a coarser tolerance keeps fewer vertices, zero keeps all`() {
        val line = walk(Random(9), 1000)
        val counts = doubleArrayOf(0.0, 1.0, 4.0, 16.0, 64.0).map { LineSimplifier.simplify(line, 0, line.size - 1, it).kept.size }
        assertEquals(1000, counts.first())
        for (i in 1 until counts.size) assertTrue(counts.toString(), counts[i] <= counts[i - 1])
        assertTrue(counts.toString(), counts.last() < 100)
    }

    @Test
    fun `a straight run collapses to its ends with the mean speed`() {
        val line = TrackLine.of(List(11) { i ->
            TrackPoint(trackId = 1, segment = 0, timestamp = i * 1000L, lat = 55.0 + i * 1e-5, lon = 37.0, altitudeM = null,
                accuracyM = 5f, speedMps = if (i < 5) 1f else 3f, bearingDeg = null)
        })
        val s = LineSimplifier.simplify(line, 0, 10, 1.0)
        assertArrayEquals(intArrayOf(0, 10), s.kept)
        val mean = (0 until 10).sumOf { line.speedMps(it).toDouble() } / 10
        assertEquals(mean, s.segmentMeans.single().toDouble(), 1e-5)
    }

    @Test
    fun `a sub-range is simplified in line indices`() {
        val line = walk(Random(2), 300)
        val s = LineSimplifier.simplify(line, 100, 200, 4.0)
        assertEquals(100, s.kept.first())
        assertEquals(200, s.kept.last())
        assertEquals(s.kept.size - 1, s.segmentMeans.size)
    }

    @Test
    fun `breaks are always kept and every stretch stays within the tolerance`() {
        val line = walk(Random(4), 400)
        val coarse = LineSimplifier.simplify(line, 0, 399, 64.0)
        val breaks = intArrayOf(37, 38, 150, 399, 500)
        val s = LineSimplifier.simplify(line, 0, 399, 64.0, breaks)
        for (b in intArrayOf(37, 38, 150, 399)) assertTrue("break $b", b in s.kept)
        assertTrue(s.kept.size <= coarse.kept.size + 3)
        for (seg in 0 until s.kept.size - 1) {
            val a = s.kept[seg]
            val b = s.kept[seg + 1]
            for (v in a..b) {
                val d = Geo.distanceToSegmentMeters(line.lat(v), line.lon(v), line.lat(a), line.lon(a), line.lat(b), line.lon(b))
                assertTrue(d <= 64.0 + 1e-6)
            }
        }
    }

    @Test
    fun `the mean of another value leaves out vertices without one`() {
        val line = TrackLine.of(List(11) { i ->
            TrackPoint(trackId = 1, segment = 0, timestamp = i * 1000L, lat = 55.0 + i * 1e-5, lon = 37.0, altitudeM = null,
                accuracyM = 5f, speedMps = 1f, bearingDeg = null)
        })
        val values = floatArrayOf(Float.NaN, 1f, 3f, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, 9f)
        val s = LineSimplifier.simplify(line, 0, 10, 1.0, intArrayOf(5)) { values[it] }
        assertArrayEquals(intArrayOf(0, 5, 10), s.kept)
        assertEquals(2f, s.segmentMeans[0], 1e-6f)
        assertTrue(s.segmentMeans[1].isNaN())
    }
}
