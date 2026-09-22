package com.justtracker.app.ui.common

import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justtracker.app.R
import com.justtracker.app.data.maps.render.HybridTileProvider
import com.justtracker.app.domain.maps.MapMode
import com.justtracker.app.domain.poi.Poi
import com.justtracker.app.util.AppLocale
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

/** Mutable state the AndroidView keeps between recompositions. */
private class MapHolder(val map: MapView) {
    val polylines = mutableListOf<Polyline>()
    val mappings = mutableListOf<SpeedMapping>()
    var positionMarker: Marker? = null
    var startMarker: Marker? = null
    var finishMarker: Marker? = null
    var highlightMarker: Marker? = null
    var fitted = false
    var lastFollowTarget: GeoPoint? = null
    var lineColor: Int = 0
    var speedColors = false
    val poiMarkers = LinkedHashMap<String, Marker>()
    var onPoiClick: ((Poi) -> Unit)? = null
    var onTrackTap: ((segment: Int, index: Int) -> Unit)? = null
}

/**
 * Colour per vertex for osmdroid's [PolychromaticPaintList]; the line between vertex i and i+1 is
 * painted with the colour of vertex i. Speeds and the scale top are swapped in place so a growing
 * live track and a rising max speed only need an invalidate, not a rebuild.
 */
private class SpeedMapping(var speeds: FloatArray, var maxMps: Double, val fallback: Int) : ColorMapping {
    override fun getColorForIndex(index: Int): Int {
        if (speeds.isEmpty()) return fallback
        return SpeedColorScale.colorForSpeed(speeds[index.coerceIn(0, speeds.size - 1)], maxMps)
    }
}

/**
 * Compose wrapper over osmdroid's MapView (docs/05_architecture.md §7).
 *
 * @param segments one polyline per recording segment; gaps between segments are not connected.
 * @param speedColors colour the line by each vertex's speed relative to [maxSpeedMps]
 *   (see [SpeedColorScale]); when false the line is drawn in [lineColor].
 * @param maxSpeedMps top of the speed colour scale (the track's max speed).
 * @param position current user position; drawn as a dot and followed when [follow] is true.
 * @param highlight vertex to mark with a small ring (the tapped section).
 * @param fitToTrack when true, the camera fits the whole track once (detail mode).
 * @param onUserGesture invoked when the user drags the map (used to disable follow mode).
 * @param onTrackTap invoked with (segment, vertex index) when the user taps the line.
 * @param pois places with a Wikipedia article drawn as pins; tapping one calls [onPoiClick].
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun TrackMap(
    segments: List<PathSegment>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    speedColors: Boolean = true,
    maxSpeedMps: Double = 0.0,
    position: GeoPoint? = null,
    highlight: GeoPoint? = null,
    follow: Boolean = false,
    fitToTrack: Boolean = false,
    showStartFinish: Boolean = false,
    onUserGesture: (() -> Unit)? = null,
    onTrackTap: ((segment: Int, index: Int) -> Unit)? = null,
    pois: List<Poi> = emptyList(),
    onPoiClick: ((Poi) -> Unit)? = null,
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
    val paddingPx = with(density) { 48.dp.toPx() }.toInt()
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
    val startColor = Color(0xFF2E7D32).toArgb()
    val finishColor = Color(0xFFC62828).toArgb()
    val highlightColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val cdMap = stringResource(R.string.cd_map)

    val holder = remember {
        val map = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(DEFAULT_ZOOM)
            overlays.add(CopyrightOverlay(context))
            if (dark) overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
        }
        MapHolder(map)
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
                    if (event.action == MotionEvent.ACTION_MOVE && onUserGesture != null) onUserGesture()
                    false
                }
            }
        },
        update = { map ->
            holder.onTrackTap = onTrackTap
            syncPolylines(holder, segments, lineColor.toArgb(), strokePx, speedColors, maxSpeedMps)
            syncStartFinish(holder, segments, showStartFinish, startColor, finishColor)
            syncPosition(holder, position, primaryArgb)
            syncHighlight(holder, highlight, highlightColor)
            holder.onPoiClick = onPoiClick
            syncPois(holder, pois)

            if (fitToTrack && !holder.fitted) {
                val all = segments.flatMap { it.points }
                if (all.size >= 2) {
                    holder.fitted = true
                    val box = BoundingBox.fromGeoPoints(all)
                    map.post { map.zoomToBoundingBox(box, false, paddingPx) }
                } else if (all.size == 1) {
                    holder.fitted = true
                    map.controller.setCenter(all.first())
                }
            }
            if (follow && position != null && position != holder.lastFollowTarget) {
                holder.lastFollowTarget = position
                if (map.zoomLevelDouble < FOLLOW_MIN_ZOOM) map.controller.setZoom(FOLLOW_ZOOM)
                map.controller.animateTo(position)
            }
            map.invalidate()
        },
    )
    }
}

private fun syncPolylines(
    holder: MapHolder,
    segments: List<PathSegment>,
    color: Int,
    strokePx: Float,
    speedColors: Boolean,
    maxSpeedMps: Double,
) {
    val map = holder.map
    // Grow the last polyline in place when only new points were appended (1 Hz live updates).
    val sameShape = holder.lineColor == color && holder.speedColors == speedColors && holder.polylines.size == segments.size &&
        holder.polylines.dropLast(1).zip(segments.dropLast(1)).all { (pl, seg) -> pl.actualPoints.size == seg.size }
    if (sameShape && holder.polylines.isNotEmpty()) {
        val last = holder.polylines.last()
        val seg = segments.last()
        val have = last.actualPoints.size
        if (seg.size >= have) {
            if (seg.size > have) last.setPoints(seg.points)
            // Speeds of already drawn vertices change slightly (median window), max may grow: refresh all.
            holder.mappings.forEachIndexed { i, m ->
                m.speeds = segments[i].speedsMps
                m.maxMps = maxSpeedMps
            }
            return
        }
    }
    holder.polylines.forEach { map.overlays.remove(it) }
    holder.polylines.clear()
    holder.mappings.clear()
    holder.lineColor = color
    holder.speedColors = speedColors
    for ((segmentIndex, seg) in segments.withIndex()) {
        val pl = Polyline(map).apply {
            outlinePaint.color = color
            outlinePaint.strokeWidth = strokePx
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            if (speedColors) {
                val mapping = SpeedMapping(seg.speedsMps, maxSpeedMps, color)
                holder.mappings.add(mapping)
                // osmdroid picks the draw mode by whichever getter was called LAST: getOutlinePaint()
                // selects the single-paint path, getOutlinePaintLists() the per-segment one. Copy the
                // paint first, touch the lists last.
                val paint = Paint(outlinePaint)
                outlinePaintLists.add(PolychromaticPaintList(paint, mapping, false))
            }
            setPoints(seg.points)
            isGeodesic = false
            infoWindow = null
            setOnClickListener { pl, _, eventPos ->
                val cb = holder.onTrackTap ?: return@setOnClickListener false
                // pl.actualPoints, not seg.points: the live polyline grows in place after creation.
                val idx = nearestIndex(pl.actualPoints, eventPos)
                if (idx >= 0) cb(segmentIndex, idx)
                idx >= 0
            }
        }
        holder.polylines.add(pl)
        map.overlays.add(0, pl)
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

private fun syncHighlight(holder: MapHolder, highlight: GeoPoint?, color: Int) {
    val map = holder.map
    if (highlight == null) {
        holder.highlightMarker?.let { map.overlays.remove(it) }
        holder.highlightMarker = null
        return
    }
    val marker = holder.highlightMarker ?: ringMarker(map, color, 18f).also {
        holder.highlightMarker = it
        map.overlays.add(it)
    }
    marker.position = highlight
}

private fun syncStartFinish(holder: MapHolder, segments: List<PathSegment>, show: Boolean, startColor: Int, finishColor: Int) {
    val map = holder.map
    val all = segments.flatMap { it.points }
    if (!show || all.size < 2) {
        holder.startMarker?.let { map.overlays.remove(it) }
        holder.finishMarker?.let { map.overlays.remove(it) }
        holder.startMarker = null
        holder.finishMarker = null
        return
    }
    if (holder.startMarker == null) {
        holder.startMarker = dotMarker(map, startColor, 16f).also { map.overlays.add(it) }
        holder.finishMarker = dotMarker(map, finishColor, 16f).also { map.overlays.add(it) }
    }
    holder.startMarker?.position = all.first()
    holder.finishMarker?.position = all.last()
}

private fun syncPosition(holder: MapHolder, position: GeoPoint?, color: Int) {
    val map = holder.map
    if (position == null) {
        holder.positionMarker?.let { map.overlays.remove(it) }
        holder.positionMarker = null
        return
    }
    val marker = holder.positionMarker ?: dotMarker(map, color, 14f).also {
        holder.positionMarker = it
        map.overlays.add(it)
    }
    marker.position = position
}

/** Adds/removes pins by POI id so unchanged markers keep their state between updates. */
private fun syncPois(holder: MapHolder, pois: List<Poi>) {
    val map = holder.map
    val wanted = pois.associateBy { it.id }
    val stale = holder.poiMarkers.keys.filter { it !in wanted }
    for (id in stale) holder.poiMarkers.remove(id)?.let { map.overlays.remove(it) }
    if (holder.poiMarkers.keys.containsAll(wanted.keys)) return
    val icon = ContextCompat.getDrawable(map.context, R.drawable.ic_poi_marker)
    for (poi in pois) {
        if (poi.id in holder.poiMarkers) continue
        val marker = Marker(map).apply {
            position = GeoPoint(poi.lat, poi.lon)
            this.icon = icon
            title = poi.name
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setInfoWindow(null)
            isDraggable = false
            setOnMarkerClickListener { _, _ ->
                holder.onPoiClick?.invoke(poi)
                true
            }
        }
        holder.poiMarkers[poi.id] = marker
        // Below the position dot (added later) but above polylines (inserted at index 0).
        map.overlays.add(marker)
    }
    holder.positionMarker?.let { pos ->
        map.overlays.remove(pos)
        map.overlays.add(pos)
    }
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
