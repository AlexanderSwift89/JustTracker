package com.justtracker.app.domain.track

import com.justtracker.app.domain.geo.Geo

/**
 * Douglas–Peucker over vertices [start]..[end] of a [TrackLine] for drawing at a coarse zoom (ADR-26): every dropped
 * vertex lies within [toleranceM] of the kept polyline, so with a tolerance below half a pixel the line looks the same
 * while the map draws a fraction of the segments. Iterative (an explicit stack, no recursion depth limit); distances
 * in a local equirectangular projection ([Geo.distanceToSegmentMeters]).
 */
object LineSimplifier {
    /** Simplified piece: [kept] line indices (ascending, both ends included) and per kept segment the mean speed. */
    class Simplified(val kept: IntArray, val segmentSpeedsMps: FloatArray)

    fun simplify(line: TrackLine, start: Int, end: Int, toleranceM: Double): Simplified {
        val n = end - start + 1
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        if (n > 2 && toleranceM > 0) {
            val stack = IntArray(2 * n)
            var top = 0
            stack[top++] = 0
            stack[top++] = n - 1
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
        // A kept segment stands for every original one it replaces: its colour is their mean speed (sub-pixel at this zoom).
        val speeds = FloatArray((kept.size - 1).coerceAtLeast(0)) { s ->
            var sum = 0.0
            for (v in kept[s] until kept[s + 1]) sum += line.speedMps(v)
            (sum / (kept[s + 1] - kept[s])).toFloat()
        }
        return Simplified(kept, speeds)
    }
}
