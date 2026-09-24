package com.justtracker.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.TileSystemWebMercator

/**
 * Regression for the rotation freeze (D-13): osmdroid computes the fit zoom from the view size minus twice the
 * padding; a negative size gives a NaN zoom, on which `Projection.getCloserPixel` loops forever.
 */
class TrackMapFitTest {
    private val tiles = TileSystemWebMercator()

    /** A ~6 × 3 km track. */
    private val box = BoundingBox(54.87, 38.56, 54.84, 38.50)

    // Pixel 7 Pro in landscape (560 dpi): the stacked detail layout gave the map ≈ 187 px of height;
    // the fit padding is 48 dp = 168 px, the minimum track viewport 32 dp = 112 px.
    private val width = 3000
    private val height = 187
    private val paddingPx = 168
    private val minViewportPx = 112

    @Test
    fun `fixed padding larger than the view gave a NaN zoom`() {
        assertTrue(tiles.getBoundingBoxZoom(box, width - 2 * paddingPx, height - 2 * paddingPx).isNaN())
    }

    @Test
    fun `padding shrinks to what fits and the zoom stays finite`() {
        val padding = fitPadding(width, height, paddingPx, minViewportPx)!!
        assertEquals((height - minViewportPx) / 2, padding)
        val zoom = tiles.getBoundingBoxZoom(box, width - 2 * padding, height - 2 * padding)
        assertTrue("zoom $zoom", zoom.isFinite())
    }

    @Test
    fun `full padding when there is room`() {
        assertEquals(paddingPx, fitPadding(1440, 1400, paddingPx, minViewportPx))
    }

    @Test
    fun `views not laid out or collapsed are not fitted`() {
        assertNull(fitPadding(0, 0, paddingPx, minViewportPx))
        assertNull(fitPadding(width, minViewportPx - 1, paddingPx, minViewportPx))
        assertEquals(0, fitPadding(minViewportPx, minViewportPx, paddingPx, minViewportPx))
    }
}
