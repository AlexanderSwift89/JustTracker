package com.justtracker.app.domain

import com.justtracker.app.domain.poi.GeoCell
import com.justtracker.app.domain.poi.OverpassQl
import com.justtracker.app.domain.poi.Poi
import com.justtracker.app.domain.poi.PoiFactory
import com.justtracker.app.domain.poi.PoiKind
import com.justtracker.app.domain.poi.PoiProximity
import com.justtracker.app.domain.poi.PoiSummary
import com.justtracker.app.domain.poi.WikipediaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikipediaRefTest {
    @Test
    fun `parses lang and title, normalising underscores`() {
        val ref = WikipediaRef.parse("ru:Красная_площадь")
        assertEquals("ru", ref?.lang)
        assertEquals("Красная площадь", ref?.title)
        assertEquals("ru:Красная_площадь", ref?.key)
    }

    @Test
    fun `rejects host injection through the language part`() {
        // A malicious tag must never become part of a hostname (docs/07_security.md).
        assertNull(WikipediaRef.parse("evil.com/x:Title"))
        assertNull(WikipediaRef.parse("en.wikipedia.org:Title"))
        assertNull(WikipediaRef.parse("en/x:Title"))
        assertNull(WikipediaRef.parse("en@evil:Title"))
        assertNull(WikipediaRef.parse(":Title"))
        assertNull(WikipediaRef.parse("Title without lang"))
        assertNull(WikipediaRef.parse(null))
    }

    @Test
    fun `accepts regional language codes`() {
        assertEquals("zh-yue", WikipediaRef.parse("zh-yue:香港")?.lang)
        assertEquals("be-tarask", WikipediaRef.parse("be-tarask:Менск")?.lang)
    }

    @Test
    fun `rejects titles with fragments or query characters`() {
        assertNull(WikipediaRef.parse("en:Title#Section"))
        assertNull(WikipediaRef.parse("en:Title?x=1"))
    }

    @Test
    fun `urls encode the title as a single path segment`() {
        val ref = WikipediaRef("en", "AC/DC (band)")
        assertEquals("https://en.wikipedia.org/api/rest_v1/page/summary/AC%2FDC_(band)", ref.summaryUrl)
        assertEquals("https://en.m.wikipedia.org/wiki/AC%2FDC_(band)", ref.pageUrl)
        assertEquals("%D0%9C%D0%BE%D1%81%D0%BA%D0%B2%D0%B0", WikipediaRef.encodeTitle("Москва"))
    }
}

class PoiFactoryTest {
    private val base = mapOf("name" to "Museum", "wikipedia" to "en:Museum", "tourism" to "museum")

    @Test
    fun `builds poi with id, kind and article`() {
        val poi = PoiFactory.fromTags("way", 42, 55.0, 37.0, base, "ru")
        assertNotNull(poi)
        assertEquals("way/42", poi!!.id)
        assertEquals(PoiKind.MUSEUM, poi.kind)
        assertEquals(WikipediaRef("en", "Museum"), poi.wikipedia)
    }

    @Test
    fun `prefers localized name and article when present`() {
        val tags = base + mapOf("name:ru" to "Музей", "wikipedia:ru" to "Музей_(Москва)")
        val poi = PoiFactory.fromTags("node", 1, 0.0, 0.0, tags, "ru")!!
        assertEquals("Музей", poi.name)
        assertEquals(WikipediaRef("ru", "Музей (Москва)"), poi.wikipedia)
    }

    @Test
    fun `falls back to default article when localized tag is malformed`() {
        val tags = base + mapOf("wikipedia:ru" to "")
        assertEquals(WikipediaRef("en", "Museum"), PoiFactory.fromTags("node", 1, 0.0, 0.0, tags, "ru")!!.wikipedia)
    }

    @Test
    fun `returns null without name or without article`() {
        assertNull(PoiFactory.fromTags("node", 1, 0.0, 0.0, base - "name", "en"))
        assertNull(PoiFactory.fromTags("node", 1, 0.0, 0.0, base - "wikipedia", "en"))
        assertNull(PoiFactory.fromTags("node", 1, 0.0, 0.0, base + ("wikipedia" to "bad"), "en"))
    }

    @Test
    fun `classifies kinds by tags`() {
        assertEquals(PoiKind.WORSHIP, PoiFactory.kindOf(mapOf("amenity" to "place_of_worship")))
        assertEquals(PoiKind.WORSHIP, PoiFactory.kindOf(mapOf("building" to "cathedral", "historic" to "yes")))
        assertEquals(PoiKind.HISTORIC, PoiFactory.kindOf(mapOf("historic" to "monument")))
        assertEquals(PoiKind.ATTRACTION, PoiFactory.kindOf(mapOf("tourism" to "viewpoint")))
        assertEquals(PoiKind.ATTRACTION, PoiFactory.kindOf(mapOf("amenity" to "theatre")))
        assertEquals(PoiKind.NATURE, PoiFactory.kindOf(mapOf("leisure" to "park")))
        assertEquals(PoiKind.NATURE, PoiFactory.kindOf(mapOf("natural" to "peak")))
        assertEquals(PoiKind.PLACE, PoiFactory.kindOf(mapOf("shop" to "books")))
    }
}

class OverpassQlTest {
    @Test
    fun `cell snaps position to a coarse grid`() {
        val cell = GeoCell.of(55.7558, 37.6173)
        assertEquals(GeoCell(11151, 7523), cell)
        assertEquals(55.7575, cell.centerLat, 1e-9)
        assertEquals(37.6175, cell.centerLon, 1e-9)
        // Nearby positions share the cell → one request, no precise coordinates leave the device.
        assertEquals(cell, GeoCell.of(55.7551, 37.6151))
        assertEquals(GeoCell(-1, -1), GeoCell.of(-0.001, -0.001))
    }

    @Test
    fun `cell query uses center coordinates with 4 decimals and dot separator`() {
        val q = OverpassQl.aroundCell(GeoCell.of(55.7558, 37.6173))
        assertTrue(q, q.contains("(around:1500,55.7575,37.6175)"))
        assertTrue(q, q.startsWith("[out:json][timeout:20];"))
        assertTrue(q, q.endsWith("out center 200;"))
        assertTrue(q, q.contains("[\"wikipedia\"]"))
        // Locale.ROOT formatting: never "55,7575" on a Russian device.
        assertFalse(q, q.contains("55,7") || q.contains("37,6"))
    }

    @Test
    fun `polyline query simplifies long tracks`() {
        val points = List(1000) { i -> (55.0 + i * 0.0001) to (37.0 + i * 0.0001) }
        val q = OverpassQl.aroundPolyline(points)!!
        val coords = q.substringAfter("around:400,").substringBefore(")").split(",")
        assertEquals(OverpassQl.MAX_POLYLINE_VERTICES * 2, coords.size)
        assertEquals("55.0000", coords.first())
        assertEquals("55.0999", coords[coords.size - 2])
    }

    @Test
    fun `polyline query needs at least two points`() {
        assertNull(OverpassQl.aroundPolyline(emptyList()))
        assertNull(OverpassQl.aroundPolyline(listOf(1.0 to 2.0)))
        assertNotNull(OverpassQl.aroundPolyline(listOf(1.0 to 2.0, 1.001 to 2.0)))
    }

    @Test
    fun `simplify keeps endpoints and size`() {
        val points = List(11) { i -> i.toDouble() to 0.0 }
        val s = OverpassQl.simplify(points, 5)
        assertEquals(listOf(0.0, 2.5, 5.0, 7.5, 10.0).map { Math.round(it).toDouble() }, s.map { it.first })
        assertEquals(points, OverpassQl.simplify(points, 11))
    }
}

class PoiProximityTest {
    private fun poi(id: String, lat: Double, lon: Double) = Poi(id, id, lat, lon, PoiKind.PLACE, WikipediaRef("en", id))

    // ~111 m per 0.001° of latitude.
    private val near = poi("near", 55.0010, 37.0)
    private val nearer = poi("nearer", 55.0005, 37.0)
    private val far = poi("far", 55.0100, 37.0)

    @Test
    fun `returns nearest within radius`() {
        assertEquals(nearer, PoiProximity.nextToAnnounce(listOf(far, near, nearer), 55.0, 37.0, emptySet()))
    }

    @Test
    fun `skips already announced`() {
        assertEquals(near, PoiProximity.nextToAnnounce(listOf(far, near, nearer), 55.0, 37.0, setOf("nearer")))
        assertNull(PoiProximity.nextToAnnounce(listOf(far, near, nearer), 55.0, 37.0, setOf("nearer", "near")))
    }

    @Test
    fun `nearest ranks by distance and limits`() {
        assertEquals(listOf(nearer, near), PoiProximity.nearest(listOf(far, near, nearer), 55.0, 37.0, 2))
    }

    @Test
    fun `nearestToLine keeps places closest to any vertex`() {
        val line = listOf(55.0 to 37.0, 55.0100 to 37.0)
        val ranked = PoiProximity.nearestToLine(listOf(near, far, nearer), line, 2)
        // `far` sits exactly on the second vertex, so it outranks `near`.
        assertEquals(listOf(far, nearer).toSet(), ranked.toSet())
        assertEquals(3, PoiProximity.nearestToLine(listOf(near, far, nearer), line, 5).size)
    }

    @Test
    fun `nothing outside radius`() {
        assertNull(PoiProximity.nextToAnnounce(listOf(far), 55.0, 37.0, emptySet()))
        assertNull(PoiProximity.nextToAnnounce(emptyList(), 55.0, 37.0, emptySet()))
    }
}

class PoiSummaryTest {
    private fun summary(text: String) = PoiSummary("T", text, "en", "https://en.m.wikipedia.org/wiki/T")

    @Test
    fun `short extract is spoken whole`() {
        assertEquals("Short text.", summary("Short text.").spokenIntro())
    }

    @Test
    fun `long extract is cut at a sentence boundary`() {
        val lead = "The first sentence describes the place in reasonable detail. The second sentence adds a bit of history to it."
        val text = "$lead Third sentence " + "x".repeat(400)
        assertEquals(lead, summary(text).spokenIntro(320))
    }

    @Test
    fun `no sentence boundary falls back to hard cut with ellipsis`() {
        val text = "y".repeat(500)
        val spoken = summary(text).spokenIntro(100)
        assertEquals(101, spoken.length)
        assertTrue(spoken.endsWith("…"))
    }
}
