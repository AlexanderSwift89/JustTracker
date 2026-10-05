package com.justtracker.app.ui.common

import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justtracker.app.R
import com.justtracker.app.data.maps.render.HybridTileProvider
import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.domain.geo.LatLon
import com.justtracker.app.domain.track.TrackLine
import com.justtracker.app.util.AppLocale
import com.justtracker.app.util.traced
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.advancedpolyline.ColorMapping
import org.osmdroid.views.overlay.advancedpolyline.PolychromaticPaintList

/**
 * Camera of a [TrackMap] kept across activity recreation (theme or language change, process death):
 * a rotation no longer recreates the activity, but these still do. [userMoved] — the user panned or
 * zoomed, so the camera must not be re-fitted to the track.
 */
private class MapCamera(
    var latitude: Double = Double.NaN,
    var longitude: Double = Double.NaN,
    var zoom: Double = Double.NaN,
    var userMoved: Boolean = false,
) {
    val isSet: Boolean get() = !latitude.isNaN() && !longitude.isNaN() && !zoom.isNaN()

    companion object {
        val Saver: Saver<MapCamera, DoubleArray> = Saver(
            save = { doubleArrayOf(it.latitude, it.longitude, it.zoom, if (it.userMoved) 1.0 else 0.0) },
            restore = { MapCamera(it[0], it[1], it[2], it[3] != 0.0) },
        )
    }
}

/** Mutable state the AndroidView keeps between recompositions. */
private class MapHolder(val map: MapView, val camera: MapCamera) {
    /** Pieces of the drawn line, in line order; see [syncLine]. */
    val pieces = mutableListOf<LinePiece>()
    /** The snapshot the pieces were drawn from. */
    var line: TrackLine? = null
    var positionMarker: Marker? = null
    var startMarker: Marker? = null
    var finishMarker: Marker? = null
    var highlightMarker: Marker? = null
    var lastFollowTarget: LatLon? = null
    var lineColor: Int = 0
    var speedColors = false
    var maxSpeedMps = 0.0
    var onTrackTap: ((index: Int) -> Unit)? = null
    var onUserGesture: (() -> Unit)? = null

    /** Uptime of the last touch on the map: camera changes right after one are the user's. */
    var lastTouchUptime = 0L

    /** Fit-to-track (detail): the track's bounds and the view size the camera was last fitted for. */
    var fitBox: BoundingBox? = null
    var fitPaddingPx = 0
    var fitMinViewportPx = 0
    var fittedWidth = 0
    var fittedHeight = 0
    val fitRunnable = Runnable { fitIfNeeded() }

    /**
     * Fits the track once per view size while the user has not moved the camera: first layout, rotation,
     * the stats panel folding in or out. Size changes of an animation are coalesced into one fit.
     */
    fun requestFit(immediately: Boolean) {
        map.removeCallbacks(fitRunnable)
        if (immediately) fitIfNeeded() else map.postDelayed(fitRunnable, FIT_SETTLE_MS)
    }

    private fun fitIfNeeded() {
        val box = fitBox ?: return
        if (camera.userMoved) return
        if (map.width == fittedWidth && map.height == fittedHeight) return
        if (fitCamera(map, box, fitPaddingPx, fitMinViewportPx)) {
            fittedWidth = map.width
            fittedHeight = map.height
        }
    }

    fun rememberCamera() {
        val center = map.mapCenter
        camera.latitude = center.latitude
        camera.longitude = center.longitude
        camera.zoom = map.zoomLevelDouble
        if (SystemClock.uptimeMillis() - lastTouchUptime < USER_CAMERA_WINDOW_MS) camera.userMoved = true
    }
}

/**
 * Fits [box] into the map with up to [paddingPx] around it. osmdroid derives the zoom from the view size
 * minus twice the padding: for a view smaller than that (the 45 %-high detail map in landscape, split
 * screen, a view not laid out yet) the zoom is NaN and `Projection.getCloserPixel` loops forever on the
 * main thread — the app froze after a rotation (D-13). The padding shrinks with the view, and nothing
 * happens until the view is at least [minViewportPx] in both directions. Returns whether it fitted.
 */
private fun fitCamera(map: MapView, box: BoundingBox, paddingPx: Int, minViewportPx: Int): Boolean {
    val width = map.width
    val height = map.height
    val padding = fitPadding(width, height, paddingPx, minViewportPx) ?: return false
    val zoom = MapView.getTileSystem().getBoundingBoxZoom(box, width - 2 * padding, height - 2 * padding)
    if (zoom.isNaN() || zoom.isInfinite()) return false
    map.zoomToBoundingBox(box, false, padding, FIT_MAX_ZOOM, null)
    return true
}

/**
 * Padding for fitting a track into a [width] × [height] px view: at most [paddingPx], but leaving at
 * least [minViewportPx] for the track in both directions; null while the view is smaller than that.
 */
internal fun fitPadding(width: Int, height: Int, paddingPx: Int, minViewportPx: Int): Int? {
    if (width < minViewportPx || height < minViewportPx) return null
    return paddingPx.coerceAtMost((minOf(width, height) - minViewportPx) / 2).coerceAtLeast(0)
}

/**
 * Colour per vertex for osmdroid's [PolychromaticPaintList] of one piece of the line: the line between vertex i and
 * i+1 of the piece is painted with the colour of line vertex [start] + i. The snapshot and the scale top are swapped
 * in place, so a growing live track (whose last vertex's speed is still provisional) and a rising max speed only
 * need an invalidate, not a rebuild.
 */
private class SpeedMapping(var line: TrackLine, val start: Int, val end: Int, var maxMps: Double) : ColorMapping {
    override fun getColorForIndex(index: Int): Int =
        SpeedColorScale.colorForSpeed(line.speedMps((start + index).coerceIn(start, minOf(end, line.size - 1))), maxMps)
}

/** One polyline of the line: vertices [start]..[end] (inclusive) of a [TrackLine], within one segment and chunk. */
private class LinePiece(val start: Int, val end: Int, val polyline: Polyline, val mapping: SpeedMapping?)

/**
 * Compose wrapper over osmdroid's MapView (docs/05_architecture.md §7).
 *
 * @param line the track; recording segments are not connected to each other.
 * @param speedColors colour the line by each vertex's speed relative to [maxSpeedMps]
 *   (see [SpeedColorScale]); when false the line is drawn in [lineColor].
 * @param maxSpeedMps top of the speed colour scale (the track's max speed).
 * @param position current user position; drawn as a dot and followed when [follow] is true.
 * @param highlight vertex to mark with a small ring (the tapped section).
 * @param fitToTrack when true, the camera fits the whole track for every new view size until the
 *   user pans or zooms (detail mode); the camera itself survives activity recreation.
 * @param onUserGesture invoked when the user drags the map (used to disable follow mode).
 * @param onTrackTap invoked with the line vertex index when the user taps the line.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun TrackMap(
    line: TrackLine,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    speedColors: Boolean = true,
    maxSpeedMps: Double = 0.0,
    position: LatLon? = null,
    highlight: LatLon? = null,
    follow: Boolean = false,
    fitToTrack: Boolean = false,
    showStartFinish: Boolean = false,
    onUserGesture: (() -> Unit)? = null,
    onTrackTap: ((index: Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Follows the *app* theme (Settings → Theme), not only the system one.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val container = LocalAppContainer.current
    val settings by container.settingsFlow.collectAsStateWithLifecycle()
    val coverage by container.offlineRegionStore.readyCoverage.collectAsStateWithLifecycle()
    val offlineFiles = coverage.map { it.file }
    val mapLanguage = AppLocale.current(settings).language
    val mapMode = settings.mapMode
    val density = LocalDensity.current
    val strokePx = with(density) { 6.dp.toPx() }
    val followSnapPx = with(density) { FOLLOW_SNAP.toPx() }
    val paddingPx = with(density) { 48.dp.toPx() }.toInt()
    val minViewportPx = with(density) { FIT_MIN_VIEWPORT.toPx() }.toInt()
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
    val startColor = Color(0xFF2E7D32).toArgb()
    val finishColor = Color(0xFFC62828).toArgb()
    val highlightColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val cdMap = stringResource(R.string.cd_map)
    val camera = rememberSaveable(saver = MapCamera.Saver) { MapCamera() }

    val holder = remember {
        val map = MapView(context).apply {
            // The view may be detached from the window and attached again while it lives on — the detail map
            // moves between the portrait and the landscape layout (movableContentOf). osmdroid would destroy
            // itself on that detach (tile provider and overlays gone, grey map); the DisposableEffect below
            // destroys it when the composable really leaves the composition.
            setDestroyMode(false)
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            if (camera.isSet) {
                controller.setZoom(camera.zoom)
                controller.setCenter(GeoPoint(camera.latitude, camera.longitude))
            } else {
                controller.setZoom(DEFAULT_ZOOM)
            }
            overlays.add(CopyrightOverlay(context))
            if (dark) overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
        }
        MapHolder(map, camera).also { h ->
            map.addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    h.rememberCamera()
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    h.rememberCamera()
                    return false
                }
            })
            // A new size (rotation, split screen, stats panel) re-fits an untouched detail map.
            map.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) h.requestFit(immediately = h.fittedWidth == 0)
            }
        }
    }

    // Tile chain is rebuilt only when the map mode, the set of ready regions (offline only) or the label language changes.
    val regionFiles = if (mapMode == MapMode.OFFLINE) offlineFiles else emptyList()
    DisposableEffect(mapMode, regionFiles, mapLanguage) {
        val provider = HybridTileProvider.create(context, mapMode, regionFiles, mapLanguage, { container.offlineRenderTheme }) {
            container.offlineRegionStore.readyCoverage.value
        }
        holder.map.tileProvider = provider // detaches the previous provider and creates a fresh TilesOverlay
        holder.map.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
        onDispose { }
    }
    // Theme changes only touch the overlay filter; the provider (and its rendered tiles) is kept.
    LaunchedEffect(dark) {
        holder.map.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
        holder.map.invalidate()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder.map.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.map.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.map.removeCallbacks(holder.fitRunnable)
            holder.map.onDetach()
        }
    }

    Box(modifier = modifier.clipToBounds()) {
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = cdMap },
        factory = {
            holder.map.apply {
                setOnTouchListener { _, event ->
                    holder.lastTouchUptime = SystemClock.uptimeMillis()
                    if (event.actionMasked == MotionEvent.ACTION_MOVE) holder.onUserGesture?.invoke()
                    false
                }
            }
        },
        update = { map -> traced("map.update") {
            holder.onTrackTap = onTrackTap
            holder.onUserGesture = onUserGesture
            // Only a real change redraws the map: an unchanged recomposition used to invalidate it as well (D-28).
            var changed = syncLine(holder, line, lineColor.toArgb(), strokePx, speedColors, maxSpeedMps)
            changed = syncStartFinish(holder, line, showStartFinish, startColor, finishColor) || changed
            changed = syncPosition(holder, position, primaryArgb) || changed
            changed = syncHighlight(holder, highlight, highlightColor) || changed

            if (fitToTrack && holder.fitBox == null) {
                line.bounds?.let { b ->
                    holder.fitBox = BoundingBox(b.maxLat, b.maxLon, b.minLat, b.minLon)
                    holder.fitPaddingPx = paddingPx
                    holder.fitMinViewportPx = minViewportPx
                    // Before the first layout the view has no size; the layout listener fits it then.
                    holder.requestFit(immediately = true)
                }
            }
            if (follow && position != null && position != holder.lastFollowTarget) {
                holder.lastFollowTarget = position
                if (map.zoomLevelDouble < FOLLOW_MIN_ZOOM) map.controller.setZoom(FOLLOW_ZOOM)
                followTo(map, GeoPoint(position.lat, position.lon), followSnapPx)
            }
            if (changed) map.invalidate()
        } },
    )
    }
}

/**
 * Draws [line] as pieces — a segment cut at chunk boundaries, each piece also holding the first vertex of the next
 * chunk so the line has no gaps. While a recording grows (same generation, more vertices) only the pieces from the
 * previously last vertex on are rebuilt: O(chunk) per fix instead of the whole track (ADR-25); osmdroid also skips
 * pieces outside the view. A new generation, colour or colour mode rebuilds everything. Returns whether anything changed.
 */
private fun syncLine(
    holder: MapHolder,
    line: TrackLine,
    color: Int,
    strokePx: Float,
    speedColors: Boolean,
    maxSpeedMps: Double,
): Boolean {
    val previous = holder.line
    if (previous === line && holder.lineColor == color && holder.speedColors == speedColors && holder.maxSpeedMps == maxSpeedMps) return false
    val map = holder.map
    val extends = previous != null && previous.generation == line.generation && line.size >= previous.size &&
        holder.lineColor == color && holder.speedColors == speedColors
    // Vertices before the previously last one are final: pieces that end before it are kept as they are.
    val keepBefore = if (extends) previous!!.size - 1 else 0
    var kept = 0
    if (extends) {
        while (kept < holder.pieces.size && holder.pieces[kept].end < keepBefore) kept++
    }
    for (i in holder.pieces.size - 1 downTo kept) map.overlays.remove(holder.pieces.removeAt(i).polyline)
    holder.lineColor = color
    holder.speedColors = speedColors
    holder.maxSpeedMps = maxSpeedMps
    holder.line = line
    for (piece in holder.pieces) piece.mapping?.let {
        it.line = line
        it.maxMps = maxSpeedMps
    }
    val from = holder.pieces.lastOrNull()?.end ?: 0
    forEachPiece(line) { a, b ->
        if (b <= from && holder.pieces.isNotEmpty()) return@forEachPiece
        holder.pieces.add(newPiece(holder, line, a, b, color, strokePx, speedColors, maxSpeedMps))
    }
    return true
}

/** Calls [action] with the first and last vertex of every piece of [line], in order (single-vertex pieces skipped). */
private inline fun forEachPiece(line: TrackLine, action: (start: Int, end: Int) -> Unit) {
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

private fun newPiece(
    holder: MapHolder,
    line: TrackLine,
    start: Int,
    end: Int,
    color: Int,
    strokePx: Float,
    speedColors: Boolean,
    maxSpeedMps: Double,
): LinePiece {
    val map = holder.map
    var mapping: SpeedMapping? = null
    val pl = Polyline(map).apply {
        outlinePaint.color = color
        outlinePaint.strokeWidth = strokePx
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        if (speedColors) {
            mapping = SpeedMapping(line, start, end, maxSpeedMps)
            // osmdroid picks the draw mode by whichever getter was called LAST: getOutlinePaint()
            // selects the single-paint path, getOutlinePaintLists() the per-segment one. Copy the
            // paint first, touch the lists last.
            val paint = Paint(outlinePaint)
            outlinePaintLists.add(PolychromaticPaintList(paint, mapping, false))
        }
        setPoints((start..end).map { GeoPoint(line.lat(it), line.lon(it)) })
        isGeodesic = false
        infoWindow = null
        setOnClickListener { pl, _, eventPos ->
            val cb = holder.onTrackTap ?: return@setOnClickListener false
            val idx = nearestIndex(pl.actualPoints, eventPos)
            if (idx >= 0) cb(start + idx)
            idx >= 0
        }
    }
    map.overlays.add(0, pl)
    return LinePiece(start, end, pl, mapping)
}

/**
 * Keeps the followed position centred. A step of a few pixels (walking, cycling at street zoom) is a plain re-centre —
 * one frame; a longer one glides in [FOLLOW_ANIMATION_MS]. The default one-second animation on every fix kept the map
 * drawing 60 frames a second for the whole recording (D-29).
 */
private fun followTo(map: MapView, position: GeoPoint, snapPx: Float) {
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
private fun nearestIndex(points: List<GeoPoint>, target: GeoPoint): Int {
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

private fun syncHighlight(holder: MapHolder, highlight: LatLon?, color: Int): Boolean {
    val map = holder.map
    if (highlight == null) {
        val marker = holder.highlightMarker ?: return false
        map.overlays.remove(marker)
        holder.highlightMarker = null
        return true
    }
    val marker = holder.highlightMarker ?: ringMarker(map, color, 18f).also {
        holder.highlightMarker = it
        map.overlays.add(it)
    }
    return marker.moveTo(highlight)
}

private fun syncStartFinish(holder: MapHolder, line: TrackLine, show: Boolean, startColor: Int, finishColor: Int): Boolean {
    val map = holder.map
    if (!show || line.size < 2) {
        if (holder.startMarker == null) return false
        holder.startMarker?.let { map.overlays.remove(it) }
        holder.finishMarker?.let { map.overlays.remove(it) }
        holder.startMarker = null
        holder.finishMarker = null
        return true
    }
    val start = holder.startMarker ?: dotMarker(map, startColor, 16f).also {
        holder.startMarker = it
        map.overlays.add(it)
    }
    val finish = holder.finishMarker ?: dotMarker(map, finishColor, 16f).also {
        holder.finishMarker = it
        map.overlays.add(it)
    }
    val movedStart = start.moveTo(line.point(0))
    return finish.moveTo(line.point(line.size - 1)) || movedStart
}

private fun syncPosition(holder: MapHolder, position: LatLon?, color: Int): Boolean {
    val map = holder.map
    if (position == null) {
        val marker = holder.positionMarker ?: return false
        map.overlays.remove(marker)
        holder.positionMarker = null
        return true
    }
    val marker = holder.positionMarker ?: dotMarker(map, color, 14f).also {
        holder.positionMarker = it
        map.overlays.add(it)
    }
    return marker.moveTo(position)
}

/** Moves the marker to [point]; false when it already stands there. */
private fun Marker.moveTo(point: LatLon): Boolean {
    val current = position
    if (current != null && current.latitude == point.lat && current.longitude == point.lon) return false
    position = GeoPoint(point.lat, point.lon)
    return true
}

private fun dotMarker(map: MapView, color: Int, sizeDp: Float): Marker {
    val density = map.resources.displayMetrics.density
    val sizePx = (sizeDp * density).toInt()
    val drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke((3 * density).toInt(), android.graphics.Color.WHITE)
        setSize(sizePx, sizePx)
    }
    return Marker(map).apply {
        icon = drawable
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setInfoWindow(null)
        isDraggable = false
    }
}

/** Hollow ring used to mark the tapped vertex without hiding the line colour underneath. */
private fun ringMarker(map: MapView, color: Int, sizeDp: Float): Marker {
    val density = map.resources.displayMetrics.density
    val sizePx = (sizeDp * density).toInt()
    val drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(android.graphics.Color.TRANSPARENT)
        setStroke((3 * density).toInt(), color)
        setSize(sizePx, sizePx)
    }
    return Marker(map).apply {
        icon = drawable
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setInfoWindow(null)
        isDraggable = false
    }
}

private const val DEFAULT_ZOOM = 4.0
private const val FOLLOW_ZOOM = 17.0
private const val FOLLOW_MIN_ZOOM = 14.0

/** Up to this distance from the centre a new position is re-centred without animation. */
private val FOLLOW_SNAP = 12.dp

/** Glide to a farther position: short, so the map is still most of every second between fixes. */
private const val FOLLOW_ANIMATION_MS = 300L

/** Closest zoom a fit may choose (a one-point or very short track). */
private const val FIT_MAX_ZOOM = 18.0

/** Below this in either direction the map is not fitted at all (it is collapsed or not laid out). */
private val FIT_MIN_VIEWPORT = 32.dp

/** Size changes of an animation (stats panel, 250 ms) are fitted once, after they settle. */
private const val FIT_SETTLE_MS = 150L

/** A camera move this soon after a touch on the map is the user's, not a fit or the follow animation. */
private const val USER_CAMERA_WINDOW_MS = 600L
