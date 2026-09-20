package com.justtracker.app.domain

import android.app.DownloadManager
import com.justtracker.app.data.maps.RegionCatalogParser
import com.justtracker.app.data.maps.RegionDownloader
import com.justtracker.app.domain.maps.LatLonBox
import com.justtracker.app.domain.maps.MapSource
import com.justtracker.app.domain.maps.MapSourceResolver
import com.justtracker.app.domain.maps.RegionCoverage
import com.justtracker.app.domain.maps.RegionError
import com.justtracker.app.domain.maps.RegionEvent
import com.justtracker.app.domain.maps.RegionStatus
import com.justtracker.app.domain.maps.RegionTransitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatLonBoxTest {
    @Test
    fun `zoom 0 tile is the whole Web Mercator world`() {
        val world = LatLonBox.ofTile(0, 0, 0)
        assertEquals(-180.0, world.minLon, 1e-9)
        assertEquals(180.0, world.maxLon, 1e-9)
        assertEquals(85.0511, world.maxLat, 1e-3)
        assertEquals(-85.0511, world.minLat, 1e-3)
    }

    @Test
    fun `zoom 1 tiles are quadrants`() {
        val nw = LatLonBox.ofTile(1, 0, 0)
        assertEquals(-180.0, nw.minLon, 1e-9)
        assertEquals(0.0, nw.maxLon, 1e-9)
        assertEquals(0.0, nw.minLat, 1e-9)
        val se = LatLonBox.ofTile(1, 1, 1)
        assertEquals(0.0, se.minLon, 1e-9)
        assertEquals(0.0, se.maxLat, 1e-9)
    }

    @Test
    fun `intersects and contains include edges`() {
        val a = LatLonBox(50.0, 30.0, 60.0, 40.0)
        assertTrue(a.intersects(LatLonBox(60.0, 40.0, 70.0, 50.0)))
        assertFalse(a.intersects(LatLonBox(60.1, 30.0, 70.0, 40.0)))
        assertTrue(a.contains(50.0, 30.0))
        assertTrue(a.contains(60.0, 40.0))
        assertFalse(a.contains(60.01, 35.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `inverted box is rejected`() {
        LatLonBox(60.0, 30.0, 50.0, 40.0)
    }
}

class MapSourceResolverTest {
    private val moscow = RegionCoverage("ru-central", "/maps/ru-central.map", LatLonBox(50.5, 30.5, 60.5, 43.5))
    private val spb = RegionCoverage("ru-northwest", "/maps/ru-northwest.map", LatLonBox(56.0, 27.0, 70.5, 66.5))

    @Test
    fun `no regions means online`() {
        assertEquals(MapSource.Online, MapSourceResolver.resolveForTile(emptyList(), 12, 2474, 1280))
    }

    @Test
    fun `tile inside a region is offline with that file`() {
        // z12 x2474 y1280 ≈ Moscow centre
        val src = MapSourceResolver.resolveForTile(listOf(moscow, spb), 12, 2474, 1280)
        assertEquals(MapSource.Offline(listOf(moscow.file)), src)
    }

    @Test
    fun `tile over two overlapping regions lists both files`() {
        // z8 x152 y78 ≈ 56.6–57.5N, 33.8–35.2E: inside both (approximate) boxes
        val src = MapSourceResolver.resolveForTile(listOf(moscow, spb), 8, 152, 78)
        assertTrue(src is MapSource.Offline)
        assertEquals(setOf(moscow.file, spb.file), (src as MapSource.Offline).files.toSet())
    }

    @Test
    fun `tile far away is online`() {
        // z10 tile near Lisbon
        assertEquals(MapSource.Online, MapSourceResolver.resolveForTile(listOf(moscow, spb), 10, 486, 393))
    }

    @Test
    fun `overview zooms always come from the online source`() {
        assertEquals(MapSource.Online, MapSourceResolver.resolveForTile(listOf(moscow), MapSourceResolver.MIN_OFFLINE_ZOOM - 1, 0, 0))
        assertEquals(MapSource.Online, MapSourceResolver.resolveForTile(listOf(moscow), 3, 4, 2))
    }
}

class RegionTransitionsTest {
    @Test
    fun `happy path download`() {
        var s: RegionStatus? = null
        s = RegionTransitions.next(s, RegionEvent.DOWNLOAD); assertEquals(RegionStatus.QUEUED, s)
        s = RegionTransitions.next(s, RegionEvent.RUNNING); assertEquals(RegionStatus.DOWNLOADING, s)
        s = RegionTransitions.next(s, RegionEvent.SUCCESS); assertEquals(RegionStatus.VERIFYING, s)
        s = RegionTransitions.next(s, RegionEvent.VERIFIED); assertEquals(RegionStatus.READY, s)
    }

    @Test
    fun `failure retry and corrupt paths`() {
        assertEquals(RegionStatus.ERROR, RegionTransitions.next(RegionStatus.DOWNLOADING, RegionEvent.FAILED))
        assertEquals(RegionStatus.QUEUED, RegionTransitions.next(RegionStatus.ERROR, RegionEvent.RETRY))
        assertEquals(RegionStatus.ERROR, RegionTransitions.next(RegionStatus.VERIFYING, RegionEvent.CORRUPT))
        assertEquals(RegionStatus.QUEUED, RegionTransitions.next(RegionStatus.DOWNLOADING, RegionEvent.PAUSED))
    }

    @Test
    fun `illegal transitions are refused`() {
        assertNull(RegionTransitions.next(RegionStatus.READY, RegionEvent.DOWNLOAD))
        assertNull(RegionTransitions.next(RegionStatus.READY, RegionEvent.RETRY))
        assertNull(RegionTransitions.next(RegionStatus.QUEUED, RegionEvent.VERIFIED))
        assertNull(RegionTransitions.next(null, RegionEvent.RUNNING))
        assertNull(RegionTransitions.next(RegionStatus.READY, RegionEvent.DELETE))
    }
}

class DownloadReasonMappingTest {
    @Test
    fun `download manager reasons map to user-facing errors`() {
        assertEquals(RegionError.NO_SPACE, RegionDownloader.errorFor(DownloadManager.ERROR_INSUFFICIENT_SPACE))
        assertEquals(RegionError.NETWORK, RegionDownloader.errorFor(DownloadManager.ERROR_HTTP_DATA_ERROR))
        assertEquals(RegionError.NETWORK, RegionDownloader.errorFor(DownloadManager.ERROR_CANNOT_RESUME))
        assertEquals(RegionError.NETWORK, RegionDownloader.errorFor(404))
        assertEquals(RegionError.CORRUPT, RegionDownloader.errorFor(DownloadManager.ERROR_FILE_ERROR))
        assertEquals(RegionError.UNKNOWN, RegionDownloader.errorFor(DownloadManager.ERROR_UNKNOWN))
    }
}

class RegionCatalogParserTest {
    private fun entry(
        id: String = "malta",
        url: String = "https://download.mapsforge.org/maps/v5/europe/malta.map",
        size: Long = 6_700_000,
        bbox: String = "[35.8, 14.2, 36.1, 14.6]",
        name: String = """{"en":"Malta","ru":"Мальта"}""",
    ) = """{"id":"$id","name":$name,"url":"$url","sizeBytes":$size,"bbox":$bbox}"""

    private fun catalog(vararg entries: String) = """{"version":1,"regions":[${entries.joinToString(",")}]}"""

    @Test
    fun `parses a valid entry`() {
        val regions = RegionCatalogParser.parse(catalog(entry()))
        assertEquals(1, regions.size)
        val r = regions.single()
        assertEquals("malta", r.id)
        assertEquals("Мальта", r.nameRu)
        assertEquals(6_700_000L, r.sizeBytes)
        assertEquals(LatLonBox(35.8, 14.2, 36.1, 14.6), r.box)
    }

    @Test
    fun `rejects http, foreign hosts and non-map paths`() {
        assertTrue(RegionCatalogParser.parse(catalog(entry(url = "http://download.mapsforge.org/maps/v5/europe/malta.map"))).isEmpty())
        assertTrue(RegionCatalogParser.parse(catalog(entry(url = "https://evil.example.org/malta.map"))).isEmpty())
        assertTrue(RegionCatalogParser.parse(catalog(entry(url = "https://download.mapsforge.org/maps/v5/europe/malta.zip"))).isEmpty())
    }

    @Test
    fun `skips malformed entries but keeps the good ones`() {
        val regions = RegionCatalogParser.parse(
            catalog(entry(), entry(id = "bad-bbox", bbox = "[1,2,3]"), entry(id = "no-size", size = 0), entry(id = "dup"), entry(id = "dup")),
        )
        assertEquals(listOf("malta", "dup"), regions.map { it.id })
    }

    @Test
    fun `garbage input gives an empty catalogue`() {
        assertTrue(RegionCatalogParser.parse("not json").isEmpty())
        assertTrue(RegionCatalogParser.parse("{}").isEmpty())
    }

    @Test
    fun `bundled catalogue asset is valid`() {
        val json = java.io.File("src/main/assets/maps/regions.json").readText()
        val regions = RegionCatalogParser.parse(json)
        assertTrue(regions.size >= 20)
        assertTrue(regions.any { it.id == "ru-central" })
        assertTrue(regions.all { it.nameEn.isNotBlank() && it.nameRu.isNotBlank() })
    }
}
