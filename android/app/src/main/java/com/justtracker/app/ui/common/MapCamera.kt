package com.justtracker.app.ui.common

import androidx.compose.runtime.saveable.Saver
import com.justtracker.app.domain.geo.Geo
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView

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
 * above the track grows to the inset — so its northern end is not hidden behind them.
 */
internal fun fitCamera(map: MapView, box: BoundingBox, paddingPx: Int, minViewportPx: Int, topInsetPx: Int = 0): Boolean {
    val width = map.width
    val height = map.height
    val padding = fitPadding(width, height, paddingPx, minViewportPx) ?: return false
    val extra = fitTopExtra(height, padding, minViewportPx, topInsetPx)
    val zoom = MapView.getTileSystem().getBoundingBoxZoom(box, width - 2 * padding, height - 2 * padding - extra)
    if (zoom.isNaN() || zoom.isInfinite()) return false
    if (extra == 0) {
        map.zoomToBoundingBox(box, false, padding, FIT_MAX_ZOOM, null)
        return true
    }
    // The box grows north by the extra pixels at the zoom it gets, so osmdroid's centred fit leaves them free on top.
    val z = minOf(zoom, FIT_MAX_ZOOM)
    val extraLat = extra * TileSystem.GroundResolution(box.centerLatitude, z) / Geo.METERS_PER_DEGREE
    map.zoomToBoundingBox(BoundingBox(box.latNorth + extraLat, box.lonEast, box.latSouth, box.lonWest), false, padding, z, null)
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
