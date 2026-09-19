package io.treklog.app.ui.common

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
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
import io.treklog.app.R
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

/** Mutable state the AndroidView keeps between recompositions. */
private class MapHolder(val map: MapView) {
    val polylines = mutableListOf<Polyline>()
    var positionMarker: Marker? = null
    var startMarker: Marker? = null
    var finishMarker: Marker? = null
    var fitted = false
    var lastFollowTarget: GeoPoint? = null
    var lineColor: Int = 0
}

/**
 * Compose wrapper over osmdroid's MapView (docs/05_architecture.md §7).
 *
 * @param segments one polyline per recording segment; gaps between segments are not connected.
 * @param position current user position; drawn as a dot and followed when [follow] is true.
 * @param fitToTrack when true, the camera fits the whole track once (detail mode).
 * @param onUserGesture invoked when the user drags the map (used to disable follow mode).
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun TrackMap(
    segments: List<List<GeoPoint>>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    position: GeoPoint? = null,
    follow: Boolean = false,
    fitToTrack: Boolean = false,
    showStartFinish: Boolean = false,
    onUserGesture: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val dark = isSystemInDarkTheme()
    val density = LocalDensity.current
    val strokePx = with(density) { 6.dp.toPx() }
    val paddingPx = with(density) { 48.dp.toPx() }.toInt()
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
    val startColor = Color(0xFF2E7D32).toArgb()
    val finishColor = Color(0xFFC62828).toArgb()
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

    AndroidView(
        modifier = modifier
            .clipToBounds()
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
            syncPolylines(holder, segments, lineColor.toArgb(), strokePx)
            syncStartFinish(holder, segments, showStartFinish, startColor, finishColor)
            syncPosition(holder, position, primaryArgb)

            if (fitToTrack && !holder.fitted) {
                val all = segments.flatten()
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

private fun syncPolylines(holder: MapHolder, segments: List<List<GeoPoint>>, color: Int, strokePx: Float) {
    val map = holder.map
    // Grow the last polyline in place when only new points were appended (1 Hz live updates).
    val sameShape = holder.lineColor == color && holder.polylines.size == segments.size &&
        holder.polylines.dropLast(1).zip(segments.dropLast(1)).all { (pl, seg) -> pl.actualPoints.size == seg.size }
    if (sameShape && holder.polylines.isNotEmpty()) {
        val last = holder.polylines.last()
        val seg = segments.last()
        val have = last.actualPoints.size
        if (seg.size >= have) {
            if (seg.size > have) last.setPoints(seg)
            return
        }
    }
    holder.polylines.forEach { map.overlays.remove(it) }
    holder.polylines.clear()
    holder.lineColor = color
    for (seg in segments) {
        val pl = Polyline(map).apply {
            outlinePaint.color = color
            outlinePaint.strokeWidth = strokePx
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
            setPoints(seg)
            isGeodesic = false
            infoWindow = null
        }
        holder.polylines.add(pl)
        map.overlays.add(0, pl)
    }
}

private fun syncStartFinish(holder: MapHolder, segments: List<List<GeoPoint>>, show: Boolean, startColor: Int, finishColor: Int) {
    val map = holder.map
    val all = segments.flatten()
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

private const val DEFAULT_ZOOM = 4.0
private const val FOLLOW_ZOOM = 17.0
private const val FOLLOW_MIN_ZOOM = 14.0
