package com.justtracker.app.ui.common

import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import com.justtracker.app.domain.geo.LatLon
import com.justtracker.app.domain.track.LineSimplifier
import com.justtracker.app.domain.track.TrackAcceleration
import com.justtracker.app.domain.track.TrackLine
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.advancedpolyline.ColorMapping
import org.osmdroid.views.overlay.advancedpolyline.PolychromaticPaintList

/**
 * How the track line is coloured (docs/04_ux_design.md §2.8, §2.13). Changing it recolours the drawn pieces in place:
 * their polylines and simplifications stay (ADR-26).
 */
sealed interface LineColoring {
    /** By each vertex's speed relative to [maxMps] ([SpeedColorScale]). */
    data class BySpeed(val maxMps: Double) : LineColoring

    /** By each vertex's acceleration ([AccelerationColorScale]); simplification keeps the episodes' ends. */
    data class ByAcceleration(val acceleration: TrackAcceleration, val palette: AccelerationColorScale.Palette) : LineColoring
}

/**
 * Colour per vertex for osmdroid's [PolychromaticPaintList] of one piece of the line: the line between vertex i and
 * i+1 of the piece is painted with the colour of line vertex [start] + i. The snapshot and the colouring are swapped
 * in place, so a growing live track (whose last vertex's speed is still provisional), a rising max speed and a switch
 * between speed and acceleration only need an invalidate, not a rebuild. Called per segment on every frame: no boxing.
 */
internal class LineColorMapping(var line: TrackLine, val start: Int, val end: Int, var coloring: LineColoring) : ColorMapping {
    /** Mean value per drawn segment when the piece is simplified; null: one segment per vertex. */
    var simplifiedValues: FloatArray? = null

    override fun getColorForIndex(index: Int): Int {
        val simplified = simplifiedValues
        val useSimplified = simplified != null && simplified.isNotEmpty()
        val vertex = (start + index).coerceIn(start, minOf(end, line.size - 1))
        return when (val c = coloring) {
            is LineColoring.BySpeed -> {
                val speed = if (useSimplified) simplified!![index.coerceIn(0, simplified.size - 1)] else line.speedMps(vertex)
                SpeedColorScale.colorForSpeed(speed, c.maxMps)
            }
            is LineColoring.ByAcceleration -> {
                val a = if (useSimplified) simplified!![index.coerceIn(0, simplified.size - 1)] else c.acceleration.mps2(vertex)
                AccelerationColorScale.colorFor(a, c.acceleration.scaleMps2, c.palette)
            }
        }
    }
}

/**
 * One polyline of the line: vertices [start]..[end] (inclusive) of a [TrackLine], within one segment and chunk. Drawn
 * at the detail [level] the zoom asks for; simplifications are cached per level and per metric (a finished piece never
 * changes; for acceleration they keep the episodes' ends).
 */
internal class LinePiece(val start: Int, val end: Int, val polyline: Polyline, val mapping: LineColorMapping) {
    var level = -1
    /** Line indices of the drawn vertices; null at full detail. */
    var kept: IntArray? = null
    private var byAcceleration = false
    private val speedCache = HashMap<Int, LineSimplifier.Simplified>()
    private val accelerationCache = HashMap<Int, LineSimplifier.Simplified>()
    private var cachedAcceleration: TrackAcceleration? = null

    /** Sets the polyline to [line] at [level] (an index into [DETAIL_TOLERANCES_M]) for [coloring]. */
    fun show(line: TrackLine, level: Int, coloring: LineColoring) {
        val acceleration = (coloring as? LineColoring.ByAcceleration)?.acceleration
        if (acceleration != null && acceleration !== cachedAcceleration) {
            accelerationCache.clear()
            cachedAcceleration = acceleration
        }
        val metricChanged = (acceleration != null) != byAcceleration
        if (level == this.level && !metricChanged) return
        byAcceleration = acceleration != null
        val wasFull = this.level == 0
        this.level = level
        if (level == 0) {
            kept = null
            mapping.simplifiedValues = null
            // Full detail draws the same vertices for either metric.
            if (!wasFull) polyline.setPoints((start..end).map { GeoPoint(line.lat(it), line.lon(it)) })
        } else {
            val tolerance = DETAIL_TOLERANCES_M[level]
            val s = if (acceleration != null) {
                accelerationCache.getOrPut(level) {
                    LineSimplifier.simplify(line, start, end, tolerance, acceleration.boundaries, acceleration::mps2)
                }
            } else {
                speedCache.getOrPut(level) { LineSimplifier.simplify(line, start, end, tolerance) }
            }
            kept = s.kept
            mapping.simplifiedValues = s.segmentMeans
            polyline.setPoints(s.kept.map { GeoPoint(line.lat(it), line.lon(it)) })
        }
    }

    /** Line index of the vertex nearest to a tap on drawn vertex [drawn]: refined to full detail around it. */
    fun lineIndexNear(line: TrackLine, drawn: Int, target: GeoPoint): Int {
        val k = kept ?: return start + drawn
        val from = k[(drawn - 1).coerceAtLeast(0)]
        val to = k[(drawn + 1).coerceAtMost(k.size - 1)]
        val i = nearestIndex((from..to).map { GeoPoint(line.lat(it), line.lon(it)) }, target)
        return if (i < 0) k[drawn] else from + i
    }
}

/**
 * Simplification tolerances by detail level, metres (ADR-26). The map uses the coarsest one below
 * [DETAIL_PIXEL_SHARE] of a screen pixel: the line looks the same, while a 100 000-point track seen whole draws a few
 * thousand segments instead of all of them on every frame.
 */
internal val DETAIL_TOLERANCES_M = doubleArrayOf(0.0, 1.0, 4.0, 16.0, 64.0, 256.0)
internal const val DETAIL_PIXEL_SHARE = 0.4

/**
 * Detail level for the map's zoom at its centre. From the zoom value itself, not the projection: inside a zoom
 * listener the projection still describes the previous frame.
 */
internal fun detailLevel(map: MapView): Int {
    val metersPerPixel = TileSystem.GroundResolution(map.mapCenter.latitude, map.zoomLevelDouble)
    if (metersPerPixel.isNaN() || metersPerPixel <= 0.0) return 0
    val allowedM = DETAIL_PIXEL_SHARE * metersPerPixel
    var level = 0
    for (i in DETAIL_TOLERANCES_M.indices) if (DETAIL_TOLERANCES_M[i] <= allowedM) level = i
    return level
}

/**
 * Draws [line] as pieces — a segment cut at chunk boundaries, each piece also holding the first vertex of the next
 * chunk so the line has no gaps. While a recording grows (same generation, more vertices) only the pieces from the
 * previously last vertex on are rebuilt: O(chunk) per fix instead of the whole track (ADR-25); osmdroid also skips
 * pieces outside the view. A new [coloring] recolours the pieces in place; a new generation rebuilds everything.
 * Returns whether anything changed.
 */
internal fun syncLine(holder: MapHolder, line: TrackLine, strokePx: Float, coloring: LineColoring): Boolean {
    val previous = holder.line
    if (previous === line && holder.coloring == coloring) return false
    val map = holder.map
    val extends = previous != null && previous.generation == line.generation && line.size >= previous.size
    // Vertices before the previously last one are final: pieces that end before it are kept as they are. The same
    // line in another colouring (speed and acceleration, a new max speed) keeps every piece.
    val keepBefore = if (extends) previous!!.size - 1 else 0
    var kept = 0
    if (previous === line) {
        kept = holder.pieces.size
    } else if (extends) {
        while (kept < holder.pieces.size && holder.pieces[kept].end < keepBefore) kept++
    }
    for (i in holder.pieces.size - 1 downTo kept) map.overlays.remove(holder.pieces.removeAt(i).polyline)
    holder.coloring = coloring
    holder.line = line
    for (piece in holder.pieces) {
        piece.mapping.line = line
        piece.mapping.coloring = coloring
    }
    holder.level = detailLevel(map)
    if (previous !== line) {
        val from = holder.pieces.lastOrNull()?.end ?: 0
        forEachPiece(line) { a, b ->
            if (b <= from && holder.pieces.isNotEmpty()) return@forEachPiece
            holder.pieces.add(newPiece(holder, line, a, b, strokePx, coloring))
        }
    }
    for (piece in holder.pieces) piece.show(line, holder.level, coloring)
    return true
}

/** Calls [action] with the first and last vertex of every piece of [line], in order (single-vertex pieces skipped). */
internal inline fun forEachPiece(line: TrackLine, action: (start: Int, end: Int) -> Unit) {
    val chunk = line.chunkSize
    for (s in 0 until line.segmentCount) {
        val segEnd = line.segmentEnd(s)
        var a = line.segmentStart(s)
        while (a < segEnd - 1) {
            val nextChunk = (a / chunk + 1) * chunk
            val b = minOf(segEnd - 1, nextChunk)
            action(a, b)
            a = b
        }
    }
}

internal fun newPiece(
    holder: MapHolder,
    line: TrackLine,
    start: Int,
    end: Int,
    strokePx: Float,
    coloring: LineColoring,
): LinePiece {
    val map = holder.map
    val mapping = LineColorMapping(line, start, end, coloring)
    val pl = Polyline(map).apply {
        outlinePaint.strokeWidth = strokePx
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        // osmdroid picks the draw mode by whichever getter was called LAST: getOutlinePaint()
        // selects the single-paint path, getOutlinePaintLists() the per-segment one. Copy the
        // paint first, touch the lists last, and never the paint again: a new colouring changes the mapping.
        val paint = Paint(outlinePaint)
        outlinePaintLists.add(PolychromaticPaintList(paint, mapping, false))
        isGeodesic = false
        infoWindow = null
    }
    val piece = LinePiece(start, end, pl, mapping)
    pl.setOnClickListener { p, _, eventPos ->
        val cb = holder.onTrackTap ?: return@setOnClickListener false
        val drawn = nearestIndex(p.actualPoints, eventPos)
        val current = holder.line
        if (drawn >= 0 && current != null) cb(piece.lineIndexNear(current, drawn, eventPos))
        drawn >= 0
    }
    map.overlays.add(0, pl)
    return piece
}

/**
 * Keeps the followed position centred. A step of a few pixels (walking, cycling at street zoom) is a plain re-centre —
 * one frame; a longer one glides in [FOLLOW_ANIMATION_MS]. The default one-second animation on every fix kept the map
 * drawing 60 frames a second for the whole recording (D-29).
 */
internal fun followTo(map: MapView, position: GeoPoint, snapPx: Float) {
    val target = map.projection.toPixels(position, null)
    val dx = (target.x - map.width / 2).toFloat()
    val dy = (target.y - map.height / 2).toFloat()
    if (dx * dx + dy * dy <= snapPx * snapPx) {
        map.controller.setCenter(position)
    } else {
        map.controller.animateTo(position, null, FOLLOW_ANIMATION_MS)
    }
}

/** Index of the vertex closest to [target] (equirectangular metric — plenty for a tap). */
internal fun nearestIndex(points: List<GeoPoint>, target: GeoPoint): Int {
    var best = -1
    var bestD = Double.MAX_VALUE
    val cosLat = kotlin.math.cos(Math.toRadians(target.latitude))
    for (i in points.indices) {
        val p = points[i]
        val dLat = p.latitude - target.latitude
        val dLon = (p.longitude - target.longitude) * cosLat
        val d = dLat * dLat + dLon * dLon
        if (d < bestD) {
            bestD = d
            best = i
        }
    }
    return best
}

internal fun syncHighlight(holder: MapHolder, highlight: LatLon?, color: Int): Boolean {
    val map = holder.map
    if (highlight == null) {
        val marker = holder.highlightMarker ?: return false
        map.overlays.remove(marker)
        holder.highlightMarker = null
        return true
    }
    val marker = holder.highlightMarker ?: circleMarker(map, color, 18f, hollow = true).also {
        holder.highlightMarker = it
        map.overlays.add(it)
    }
    return marker.moveTo(highlight)
}

internal fun syncStartFinish(holder: MapHolder, line: TrackLine, show: Boolean, startColor: Int, finishColor: Int): Boolean {
    val map = holder.map
    if (!show || line.size < 2) {
        if (holder.startMarker == null) return false
        holder.startMarker?.let { map.overlays.remove(it) }
        holder.finishMarker?.let { map.overlays.remove(it) }
        holder.startMarker = null
        holder.finishMarker = null
        return true
    }
    val start = holder.startMarker ?: circleMarker(map, startColor, 16f).also {
        holder.startMarker = it
        map.overlays.add(it)
    }
    val finish = holder.finishMarker ?: circleMarker(map, finishColor, 16f).also {
        holder.finishMarker = it
        map.overlays.add(it)
    }
    val movedStart = start.moveTo(line.point(0))
    return finish.moveTo(line.point(line.size - 1)) || movedStart
}

internal fun syncPosition(holder: MapHolder, position: LatLon?, color: Int): Boolean {
    val map = holder.map
    if (position == null) {
        val marker = holder.positionMarker ?: return false
        map.overlays.remove(marker)
        holder.positionMarker = null
        return true
    }
    val marker = holder.positionMarker ?: circleMarker(map, color, 14f).also {
        holder.positionMarker = it
        map.overlays.add(it)
    }
    return marker.moveTo(position)
}

/** Moves the marker to [point]; false when it already stands there. */
internal fun Marker.moveTo(point: LatLon): Boolean {
    val current = position
    if (current != null && current.latitude == point.lat && current.longitude == point.lon) return false
    position = GeoPoint(point.lat, point.lon)
    return true
}

/**
 * Round marker of [sizeDp] centred on its position: a filled dot with a white rim (position, start, finish) or, with
 * [hollow], a ring in [color] that leaves the line colour underneath visible (the tapped vertex).
 */
internal fun circleMarker(map: MapView, color: Int, sizeDp: Float, hollow: Boolean = false): Marker {
    val density = map.resources.displayMetrics.density
    val sizePx = (sizeDp * density).toInt()
    val drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (hollow) android.graphics.Color.TRANSPARENT else color)
        setStroke((3 * density).toInt(), if (hollow) color else android.graphics.Color.WHITE)
        setSize(sizePx, sizePx)
    }
    return Marker(map).apply {
        icon = drawable
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setInfoWindow(null)
        isDraggable = false
    }
}
