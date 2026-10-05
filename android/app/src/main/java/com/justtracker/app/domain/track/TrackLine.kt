package com.justtracker.app.domain.track

import com.justtracker.app.domain.geo.Geo
import com.justtracker.app.domain.geo.LatLon
import com.justtracker.app.domain.maps.LatLonBox
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.stats.TrackStatsCalculator
import java.util.concurrent.atomic.AtomicInteger

/**
 * Position of the detail-screen scrubber on the track: a vertex by its index over the whole line, plus everything the
 * cursor panel shows (docs/04_ux_design.md §2.9).
 */
data class TrackCursor(
    /** Vertex index over the whole line, 0 until [TrackLine.size]. */
    val index: Int,
    val point: LatLon,
    val speedMps: Float,
    val distanceFromStartM: Double,
    /** 0..1 share of the total distance — the slider position. */
    val fraction: Float,
    val elapsedMs: Long,
    val timestamp: Long,
    /** null when the fix had no altitude. */
    val altitudeM: Double?,
)

/** Vertex the user tapped on the line, resolved to the values the map cannot know. */
data class TrackTapInfo(
    val point: LatLon,
    val speedMps: Float,
    val distanceFromStartM: Double,
    /** Time since the track started at that point, ms. */
    val elapsedMs: Long,
)

/**
 * Up to a chunk's capacity of vertices, as parallel arrays. A chunk whose vertices are all final (see
 * [TrackLineBuilder]) is never written again and is shared by every later snapshot.
 */
class LineChunk internal constructor(capacity: Int) {
    internal val lat = DoubleArray(capacity)
    internal val lon = DoubleArray(capacity)
    internal val speed = FloatArray(capacity)
    internal val distance = DoubleArray(capacity)
    internal val time = LongArray(capacity)
    internal val alt = DoubleArray(capacity)

    internal fun copy(): LineChunk = LineChunk(lat.size).also { c ->
        lat.copyInto(c.lat)
        lon.copyInto(c.lon)
        speed.copyInto(c.speed)
        distance.copyInto(c.distance)
        time.copyInto(c.time)
        alt.copyInto(c.alt)
    }
}

/**
 * Immutable snapshot of a track line for the map, the tap card and the detail scrubber (docs/06_system_analysis.md
 * §3.6): per vertex the position, the smoothed speed (the same 3-point median [TrackStatsCalculator] uses for the max
 * speed, so line, legend and "Max speed" agree), the distance from the start (a segment break neither adds the gap
 * nor connects the line), the time and the altitude. Vertices are addressed by one index over the whole line.
 *
 * Built by [TrackLineBuilder], which extends a recording's line point by point without copying it (ADR-25).
 * [generation] is unique to a builder and changes when it is reset: a line built anew never shares it with an
 * earlier one, while one builder's snapshots keep it as the line is extended. A consumer that drew vertices
 * `0 until n` of an earlier snapshot of the same generation may keep them, apart from the last one, whose speed
 * changes when the next vertex arrives.
 */
class TrackLine internal constructor(
    val generation: Int,
    val size: Int,
    private val chunkShift: Int,
    private val chunks: Array<LineChunk>,
    private val segmentStarts: IntArray,
    /** Box around all vertices; null for an empty line. */
    val bounds: LatLonBox?,
) {
    private val mask = (1 shl chunkShift) - 1

    /** Vertices per chunk: the map draws a chunk of a segment as one polyline (a power of two). */
    val chunkSize: Int get() = 1 shl chunkShift

    val isEmpty: Boolean get() = size == 0

    /** Recording segments (pause, recovery, re-anchoring); the line is not drawn across their boundaries. */
    val segmentCount: Int get() = segmentStarts.size

    fun segmentStart(segment: Int): Int = segmentStarts[segment]

    /** Exclusive end of [segment]. */
    fun segmentEnd(segment: Int): Int = if (segment + 1 < segmentStarts.size) segmentStarts[segment + 1] else size

    /** Segment (0-based, in order) the vertex [index] belongs to. */
    fun segmentOf(index: Int): Int {
        var lo = 0
        var hi = segmentStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (segmentStarts[mid] <= index) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun lat(index: Int): Double = chunks[index shr chunkShift].lat[index and mask]
    fun lon(index: Int): Double = chunks[index shr chunkShift].lon[index and mask]
    fun point(index: Int): LatLon = LatLon(lat(index), lon(index))
    fun speedMps(index: Int): Float = chunks[index shr chunkShift].speed[index and mask]
    fun distanceM(index: Int): Double = chunks[index shr chunkShift].distance[index and mask]
    fun timestamp(index: Int): Long = chunks[index shr chunkShift].time[index and mask]

    /** null when the fix had no altitude. */
    fun altitudeM(index: Int): Double? = chunks[index shr chunkShift].alt[index and mask].takeUnless { it.isNaN() }

    /** Total track distance: the last vertex's cumulative distance (segments do not connect). */
    val totalDistanceM: Double get() = if (size == 0) 0.0 else distanceM(size - 1)

    /** Vertex whose cumulative distance is nearest to [fraction] × total distance (slider → vertex), within a segment. */
    fun indexForFraction(fraction: Float): Int {
        val target = totalDistanceM * fraction.coerceIn(0f, 1f)
        for (s in 0 until segmentCount) {
            val start = segmentStart(s)
            val last = segmentEnd(s) - 1
            if (last < start || target > distanceM(last)) continue
            var lo = start
            var hi = last
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (distanceM(mid) < target) lo = mid + 1 else hi = mid
            }
            // lo is the first vertex at/after target; pick the nearer of it and its predecessor in the segment.
            if (lo > start && target - distanceM(lo - 1) < distanceM(lo) - target) lo--
            return lo
        }
        return (size - 1).coerceAtLeast(0)
    }

    /** The scrubber at vertex [index]; null when out of range. */
    fun cursorAt(index: Int, startedAt: Long): TrackCursor? {
        if (index !in 0 until size) return null
        val total = totalDistanceM
        return TrackCursor(
            index = index,
            point = point(index),
            speedMps = speedMps(index),
            distanceFromStartM = distanceM(index),
            fraction = if (total > 0) (distanceM(index) / total).toFloat().coerceIn(0f, 1f) else 0f,
            elapsedMs = (timestamp(index) - startedAt).coerceAtLeast(0),
            timestamp = timestamp(index),
            altitudeM = altitudeM(index),
        )
    }

    /** Speed, distance and time of the tapped vertex [index]; null when out of range. */
    fun tapInfo(index: Int, startedAt: Long): TrackTapInfo? {
        if (index !in 0 until size) return null
        return TrackTapInfo(point(index), speedMps(index), distanceM(index), (timestamp(index) - startedAt).coerceAtLeast(0))
    }

    companion object {
        val EMPTY = TrackLineBuilder().snapshot()

        /** The whole line of [points] at once (a finished track). */
        fun of(points: List<TrackPoint>): TrackLine = TrackLineBuilder().apply { append(points) }.snapshot()
    }
}

/**
 * Builds a [TrackLine] point by point (ADR-25). Appending vertex n fixes the smoothed speed of vertex n − 1 (the
 * median of its two neighbours) and sets vertex n to its raw speed — exactly the values
 * [TrackStatsCalculator.smoothedSpeeds] gives for the same points, however they were split into calls.
 *
 * A [snapshot] shares every chunk whose vertices are all final and copies only the chunk(s) holding the last vertex,
 * so a long recording costs O(chunk) per snapshot, not O(track). Not thread-safe: one owner appends and snapshots;
 * snapshots may be read from any thread.
 *
 * @param chunkShift log2 of the vertices per chunk (tests use small chunks to cross boundaries).
 */
class TrackLineBuilder(private val chunkShift: Int = DEFAULT_CHUNK_SHIFT) {
    private val chunkSize = 1 shl chunkShift
    private val mask = chunkSize - 1
    private val chunks = ArrayList<LineChunk>()
    private var segmentStarts = IntArray(4)
    private var segmentCount = 0
    private var generation = nextGeneration()
    private var size = 0

    private var previous: TrackPoint? = null
    private var rawLast = 0f
    private var rawBeforeLast = 0f
    private var distance = 0.0
    private var minLat = Double.POSITIVE_INFINITY
    private var minLon = Double.POSITIVE_INFINITY
    private var maxLat = Double.NEGATIVE_INFINITY
    private var maxLon = Double.NEGATIVE_INFINITY

    /** Vertices appended so far. */
    val count: Int get() = size

    /** Forgets every vertex: the next snapshot is a new [TrackLine.generation]. */
    fun reset() {
        chunks.clear()
        segmentCount = 0
        size = 0
        previous = null
        rawLast = 0f
        rawBeforeLast = 0f
        distance = 0.0
        minLat = Double.POSITIVE_INFINITY
        minLon = Double.POSITIVE_INFINITY
        maxLat = Double.NEGATIVE_INFINITY
        maxLon = Double.NEGATIVE_INFINITY
        generation = nextGeneration()
    }

    /** Appends [points] in order (timestamps of a recording, as the database returns them). */
    fun append(points: List<TrackPoint>) {
        for (p in points) append(p)
    }

    fun append(p: TrackPoint) {
        val prev = previous
        val sameSegment = prev != null && prev.segment == p.segment
        val step = if (sameSegment) Geo.distanceMeters(prev!!.lat, prev.lon, p.lat, p.lon) else 0.0
        val raw = TrackStatsCalculator.rawSpeed(if (sameSegment) prev else null, p, step)
        if (prev == null || !sameSegment) addSegmentStart(size)
        distance += step

        val index = size
        if (index and mask == 0) chunks.add(LineChunk(chunkSize))
        val chunk = chunks[index shr chunkShift]
        val i = index and mask
        chunk.lat[i] = p.lat
        chunk.lon[i] = p.lon
        chunk.speed[i] = raw
        chunk.distance[i] = distance
        chunk.time[i] = p.timestamp
        chunk.alt[i] = p.altitudeM ?: Double.NaN
        // The previous vertex was the last one: now it has both neighbours and gets its median (not the first vertex).
        if (index >= 2) {
            val before = index - 1
            chunks[before shr chunkShift].speed[before and mask] = TrackStatsCalculator.median3(rawBeforeLast, rawLast, raw)
        }
        rawBeforeLast = rawLast
        rawLast = raw
        previous = p
        size++
        if (p.lat < minLat) minLat = p.lat
        if (p.lat > maxLat) maxLat = p.lat
        if (p.lon < minLon) minLon = p.lon
        if (p.lon > maxLon) maxLon = p.lon
    }

    /**
     * The line as it is now. Chunks whose last vertex is final (it has a successor) are shared; the rest — the one or
     * two chunks around the last vertex — are copied, so later appends never change a snapshot.
     */
    fun snapshot(): TrackLine {
        val shared = ((size - 1) shr chunkShift).coerceAtLeast(0) // chunks entirely before the last vertex's chunk
        val array = Array(chunks.size) { j ->
            val lastOfChunk = (j + 1) * chunkSize - 1
            if (j < shared && lastOfChunk <= size - 2) chunks[j] else chunks[j].copy()
        }
        val bounds = if (size == 0) null else LatLonBox(minLat, minLon, maxLat, maxLon)
        return TrackLine(generation, size, chunkShift, array, segmentStarts.copyOf(segmentCount), bounds)
    }

    private fun addSegmentStart(index: Int) {
        if (segmentCount == segmentStarts.size) segmentStarts = segmentStarts.copyOf(segmentCount * 2)
        segmentStarts[segmentCount++] = index
    }

    companion object {
        /** 1024 vertices per chunk: about 17 min of a 1 Hz recording, ≈ 45 KB. */
        const val DEFAULT_CHUNK_SHIFT = 10

        // Unique per process: two lines built separately never pass for one extending the other.
        private val generations = AtomicInteger()

        private fun nextGeneration(): Int = generations.incrementAndGet()
    }
}
