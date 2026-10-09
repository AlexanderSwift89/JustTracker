package com.justtracker.app.ui.common

import android.annotation.SuppressLint
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justtracker.app.R
import com.justtracker.app.data.maps.MapTiles
import com.justtracker.app.data.maps.TileConfig
import com.justtracker.app.domain.geo.LatLon
import com.justtracker.app.domain.track.TrackLine
import com.justtracker.app.ui.theme.TrackColors
import com.justtracker.app.util.traced
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay

/** Mutable state the AndroidView keeps between recompositions. */
internal class MapHolder(val map: MapView, val camera: MapCamera) {
    /** Pieces of the drawn line, in line order; see [syncLine]. */
    val pieces = mutableListOf<LinePiece>()
    /** The snapshot the pieces were drawn from. */
    var line: TrackLine? = null
    var positionMarker: Marker? = null
    var startMarker: Marker? = null
    var finishMarker: Marker? = null
    var highlightMarker: Marker? = null
    var lastFollowTarget: LatLon? = null
    /** Colouring the pieces are drawn in; see [syncLine]. */
    var coloring: LineColoring? = null
    /** Configuration the current tile provider was built for. */
    var tileConfig: TileConfig? = null
    /** Detail level the pieces are drawn at ([DETAIL_TOLERANCES_M]). */
    var level = 0
    var onTrackTap: ((index: Int) -> Unit)? = null
    var onUserGesture: (() -> Unit)? = null

    /** Uptime of the last touch on the map: camera changes right after one are the user's. */
    var lastTouchUptime = 0L

    /** Fit-to-track (detail): the track's bounds and the view size the camera was last fitted for. */
    var fitBox: BoundingBox? = null
    var fitPaddingPx = 0
    var fitMinViewportPx = 0
    var fitTopInsetPx = 0
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
        if (fitCamera(map, box, fitPaddingPx, fitMinViewportPx, fitTopInsetPx)) {
            fittedWidth = map.width
            fittedHeight = map.height
        }
    }

    /** A zoom changed the detail level: the pieces are redrawn simplified for it (cached per level). */
    fun relevel() {
        val l = detailLevel(map)
        if (l == level) return
        level = l
        val current = line ?: return
        val c = coloring ?: return
        for (piece in pieces) piece.show(current, l, c)
        map.invalidate()
    }

    fun rememberCamera() {
        val center = map.mapCenter
        camera.latitude = center.latitude
        camera.longitude = center.longitude
        camera.zoom = map.zoomLevelDouble
        if (SystemClock.uptimeMillis() - lastTouchUptime < USER_CAMERA_WINDOW_MS) camera.userMoved = true
    }
}

/** Tile configuration and provider factory of the app's maps; provided by MainActivity. */
val LocalMapTiles = staticCompositionLocalOf<MapTiles> { error("MapTiles not provided") }

/**
 * Compose wrapper over osmdroid's MapView (docs/05_architecture.md §7).
 *
 * @param line the track; recording segments are not connected to each other.
 * @param coloring the line's colours: by speed relative to the track's max speed ([SpeedColorScale]) or by
 *   acceleration ([AccelerationColorScale]).
 * @param position current user position; drawn as a dot and followed when [follow] is true.
 * @param highlight vertex to mark with a small ring (the tapped section).
 * @param fitToTrack when true, the camera fits the whole track for every new view size until the
 *   user pans or zooms (detail mode); the camera itself survives activity recreation.
 * @param fitTopInsetPx height covered by overlays at the top: the fitted track goes below it.
 * @param onUserGesture invoked when the user drags the map (used to disable follow mode).
 * @param onTrackTap invoked with the line vertex index when the user taps the line.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun TrackMap(
    line: TrackLine,
    modifier: Modifier = Modifier,
    coloring: LineColoring = LineColoring.BySpeed(0.0),
    position: LatLon? = null,
    highlight: LatLon? = null,
    follow: Boolean = false,
    fitToTrack: Boolean = false,
    fitTopInsetPx: Int = 0,
    showStartFinish: Boolean = false,
    onUserGesture: (() -> Unit)? = null,
    onTrackTap: ((index: Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Follows the *app* theme (Settings → Theme), not only the system one.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val tiles = LocalMapTiles.current
    val tileConfig by tiles.config.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val strokePx = with(density) { 6.dp.toPx() }
    val followSnapPx = with(density) { FOLLOW_SNAP.toPx() }
    val paddingPx = with(density) { 48.dp.toPx() }.toInt()
    val minViewportPx = with(density) { FIT_MIN_VIEWPORT.toPx() }.toInt()
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
    val startColor = TrackColors.start.toArgb()
    val finishColor = TrackColors.finish.toArgb()
    val highlightColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val cdMap = stringResource(R.string.cd_map)
    val camera = rememberSaveable(saver = MapCamera.Saver) { MapCamera() }

    val holder = remember {
        // Built with its own provider: MapView(context) first creates osmdroid's default one (SQLite cache, threads),
        // which the tile configuration then replaced at once.
        val initialConfig = tileConfig
        val map = MapView(context, tiles.provider(initialConfig)).apply {
            // The view may be detached from the window and attached again while it lives on — the detail map
            // moves between the portrait and the landscape layout (movableContentOf). osmdroid would destroy
            // itself on that detach (tile provider and overlays gone, grey map); the DisposableEffect below
            // destroys it when the composable really leaves the composition.
            setDestroyMode(false)
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
            h.tileConfig = initialConfig
            map.addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    h.rememberCamera()
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    h.rememberCamera()
                    h.relevel()
                    return false
                }
            })
            // A new size (rotation, split screen, stats panel) re-fits an untouched detail map.
            map.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) h.requestFit(immediately = h.fittedWidth == 0)
            }
        }
    }

    // The tile chain is rebuilt only when the map mode, the set of ready regions (offline only) or the label language
    // changes.
    LaunchedEffect(tileConfig) {
        if (tileConfig == holder.tileConfig) return@LaunchedEffect
        holder.tileConfig = tileConfig
        holder.map.tileProvider = tiles.provider(tileConfig) // detaches the previous provider and creates a fresh TilesOverlay
        holder.map.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
        holder.map.invalidate()
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
            var changed = syncLine(holder, line, strokePx, coloring)
            changed = syncStartFinish(holder, line, showStartFinish, startColor, finishColor) || changed
            changed = syncPosition(holder, position, primaryArgb) || changed
            changed = syncHighlight(holder, highlight, highlightColor) || changed

            if (fitToTrack && fitTopInsetPx != holder.fitTopInsetPx) {
                // The overlays were measured or changed (the metric switch appears, a larger font): fit again.
                holder.fitTopInsetPx = fitTopInsetPx
                holder.fittedWidth = 0
                holder.fittedHeight = 0
                if (holder.fitBox != null) holder.requestFit(immediately = false)
            }
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

internal const val DEFAULT_ZOOM = 4.0
internal const val FOLLOW_ZOOM = 17.0
internal const val FOLLOW_MIN_ZOOM = 14.0

/** Up to this distance from the centre a new position is re-centred without animation. */
internal val FOLLOW_SNAP = 12.dp

/** Glide to a farther position: short, so the map is still most of every second between fixes. */
internal const val FOLLOW_ANIMATION_MS = 300L

/** Closest zoom a fit may choose (a one-point or very short track). */
internal const val FIT_MAX_ZOOM = 18.0

/** Below this in either direction the map is not fitted at all (it is collapsed or not laid out). */
internal val FIT_MIN_VIEWPORT = 32.dp

/** Size changes of an animation (stats panel, 250 ms) are fitted once, after they settle. */
internal const val FIT_SETTLE_MS = 150L

/** A camera move this soon after a touch on the map is the user's, not a fit or the follow animation. */
internal const val USER_CAMERA_WINDOW_MS = 600L
