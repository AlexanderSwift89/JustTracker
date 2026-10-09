package com.justtracker.app.domain.track

import com.justtracker.app.domain.geo.Geo

/**
 * Douglas–Peucker over vertices [start]..[end] of a [TrackLine] for drawing at a coarse zoom (ADR-26): every dropped
 * vertex lies within [toleranceM] of the kept polyline, so with a tolerance below half a pixel the line looks the same
 * while the map draws a fraction of the segments. Iterative (an explicit stack, no recursion depth limit); distances
 * in a local equirectangular projection ([Geo.distanceToSegmentMeters]).
 */
object LineSimplifier {
    /** Simplified piece: [kept] line indices (ascending, both ends included) and per kept segment the mean value. */
    class Simplified(val kept: IntArray, val segmentMeans: FloatArray)

    private val NO_BREAKS = IntArray(0)

    /**
     * @param breaks line indices that are always kept (ascending; those outside the piece are ignored): the ends of
     *   acceleration episodes, so a short hard stop is not averaged into a long straight (§3.11, item 7).
     * @param value per-vertex value the colour of a kept segment is the mean of — speed by default; NaN values (no
     *   estimate) are left out of the mean, which is NaN when the segment has none.
     */
    fun simplify(
        line: TrackLine,
        start: Int,
        end: Int,
        toleranceM: Double,
        breaks: IntArray = NO_BREAKS,
        value: (Int) -> Float = line::speedMps,
    ): Simplified {
        val n = end - start + 1
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        for (b in breaks) if (b in start..end) keep[b - start] = true
        if (n > 2 && toleranceM > 0) {
            val stack = IntArray(2 * n)
            var top = 0
            // Each stretch between two forced vertices is simplified on its own.
            var anchor = 0
            for (k in 1 until n) {
                if (!keep[k]) continue
                stack[top++] = anchor
                stack[top++] = k
                anchor = k
            }
            while (top > 0) {
                val j = stack[--top]
                val i = stack[--top]
                if (j - i < 2) continue
                val aLat = line.lat(start + i)
                val aLon = line.lon(start + i)
                val bLat = line.lat(start + j)
                val bLon = line.lon(start + j)
                var farthest = -1
                var farthestM = toleranceM
                for (k in i + 1 until j) {
                    val d = Geo.distanceToSegmentMeters(line.lat(start + k), line.lon(start + k), aLat, aLon, bLat, bLon)
                    if (d > farthestM) {
                        farthestM = d
                        farthest = k
                    }
                }
                if (farthest > 0) {
                    keep[farthest] = true
                    stack[top++] = i
                    stack[top++] = farthest
                    stack[top++] = farthest
                    stack[top++] = j
                }
            }
        } else if (toleranceM <= 0) {
            keep.fill(true)
        }
        val kept = IntArray(keep.count { it })
        var c = 0
        for (k in 0 until n) if (keep[k]) kept[c++] = start + k
        // A kept segment stands for every original one it replaces: its colour is their mean value (sub-pixel at this zoom).
        val means = FloatArray((kept.size - 1).coerceAtLeast(0)) { s ->
            var sum = 0.0
            var count = 0
            for (v in kept[s] until kept[s + 1]) {
                val x = value(v)
                if (x.isNaN()) continue
                sum += x
                count++
            }
            if (count == 0) Float.NaN else (sum / count).toFloat()
        }
        return Simplified(kept, means)
    }
}
