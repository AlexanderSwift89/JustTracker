package com.justtracker.app.data

import android.app.DownloadManager
import com.justtracker.app.data.maps.RegionCatalogParser
import com.justtracker.app.data.maps.RegionDownloader
import com.justtracker.app.domain.maps.LatLonBox
import com.justtracker.app.domain.maps.RegionError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `rejects credentials and explicit ports on the allowed host`() {
        assertTrue(RegionCatalogParser.parse(catalog(entry(url = "https://user@download.mapsforge.org/maps/v5/europe/malta.map"))).isEmpty())
        assertTrue(RegionCatalogParser.parse(catalog(entry(url = "https://download.mapsforge.org:8443/maps/v5/europe/malta.map"))).isEmpty())
        assertTrue(RegionCatalogParser.parse(catalog(entry(url = "https://download.mapsforge.org:443/maps/v5/europe/malta.map"))).isEmpty())
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
