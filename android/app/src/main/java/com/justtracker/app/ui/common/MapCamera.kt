package com.justtracker.app.ui.common

import androidx.compose.runtime.saveable.Saver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection

/**
 * Camera of a [TrackMap] kept across activity recreation (theme or language change, process death):
 * a rotation no longer recreates the activity, but these still do. [userMoved] — the user panned or
 * zoomed, so the camera must not be re-fitted to the track.
 */
internal class MapCamera(
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

/**
 * Fits [box] into the map with up to [paddingPx] around it. osmdroid derives the zoom from the view size
 * minus twice the padding: for a view smaller than that (the 45 %-high detail map in landscape, split
 * screen, a view not laid out yet) the zoom is NaN and `Projection.getCloserPixel` loops forever on the
 * main thread — the app froze after a rotation (D-13). The padding shrinks with the view, and nothing
 * happens until the view is at least [minViewportPx] in both directions. Returns whether it fitted.
 *
 * [topInsetPx] is covered by overlays (the detail's metric switch and legend): the track goes below it — the padding
 * above the track grows to the inset — so its northern end is not hidden behind them; the bottom padding is then halved,
 * so the overlays cost the track less of a short map.
 */
internal fun fitCamera(map: MapView, box: BoundingBox, paddingPx: Int, minViewportPx: Int, topInsetPx: Int = 0): Boolean {
    val width = map.width
    val height = map.height
    val padding = fitPadding(width, height, paddingPx, minViewportPx) ?: return false
    val extra = fitTopExtra(height, padding, minViewportPx, topInsetPx)
    if (extra == 0) {
        val zoom = MapView.getTileSystem().getBoundingBoxZoom(box, width - 2 * padding, height - 2 * padding)
        if (zoom.isNaN() || zoom.isInfinite()) return false
        map.zoomToBoundingBox(box, false, padding, FIT_MAX_ZOOM, null)
        return true
    }
    // Below the overlays: the track starts under them, and at the bottom it needs only room for the finish marker.
    val top = padding + extra
    val bottom = padding / 2
    val zoom = MapView.getTileSystem().getBoundingBoxZoom(box, width - 2 * padding, height - top - bottom)
    if (zoom.isNaN() || zoom.isInfinite()) return false
    // osmdroid's fit would centre the box; here the centre moves so that the box's middle, in screen pixels (Mercator is
    // not linear in latitude), sits in the middle of the free band between the overlays and the bottom.
    val z = zoom.coerceAtMost(FIT_MAX_ZOOM).coerceIn(map.minZoomLevel, map.maxZoomLevel)
    val center = box.centerWithDateLine
    val projection = Projection(
        z, width, height, center, map.mapOrientation,
        map.isHorizontalMapRepetitionEnabled, map.isVerticalMapRepetitionEnabled, map.mapCenterOffsetX, map.mapCenterOffsetY,
    )
    val north = projection.toPixels(GeoPoint(box.actualNorth, center.longitude), null).y
    val south = projection.toPixels(GeoPoint(box.actualSouth, center.longitude), null).y
    val shift = (top + height - bottom) / 2 - (north + south) / 2
    map.controller.setZoom(z)
    map.controller.setCenter(projection.fromPixels(width / 2, height / 2 - shift))
    return true
}

/**
 * Pixels the track moves down below the top padding to clear [topInsetPx] of overlays, leaving at least
 * [minViewportPx] of height for the track: 0 when the padding already clears them.
 */
internal fun fitTopExtra(height: Int, paddingPx: Int, minViewportPx: Int, topInsetPx: Int): Int =
    (topInsetPx - paddingPx).coerceAtMost(height - 2 * paddingPx - minViewportPx).coerceAtLeast(0)

/**
 * Padding for fitting a track into a [width] × [height] px view: at most [paddingPx], but leaving at
 * least [minViewportPx] for the track in both directions; null while the view is smaller than that.
 */
internal fun fitPadding(width: Int, height: Int, paddingPx: Int, minViewportPx: Int): Int? {
    if (width < minViewportPx || height < minViewportPx) return null
    return paddingPx.coerceAtMost((minOf(width, height) - minViewportPx) / 2).coerceAtLeast(0)
}
