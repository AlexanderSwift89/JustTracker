package com.justtracker.app.domain

import com.justtracker.app.domain.geo.Geo
import com.justtracker.app.domain.poi.GeoCell
import com.justtracker.app.domain.poi.OverpassQl
import com.justtracker.app.domain.poi.Poi
import com.justtracker.app.domain.poi.PoiFactory
import com.justtracker.app.domain.poi.PoiKind
import com.justtracker.app.domain.poi.PoiProximity
import com.justtracker.app.domain.poi.PoiSummary
import com.justtracker.app.domain.poi.TrackQuery
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
    fun `accepts wikipedia page urls`() {
        listOf(
            "https://en.m.wikipedia.org/wiki/Red_Square",
            "https://ru.wikipedia.org/wiki/%D0%9A%D1%80%D0%B5%D0%BC%D0%BB%D1%8C",
            "https://wikipedia.org/",
            "HTTPS://EN.WIKIPEDIA.ORG/wiki/X",
        ).forEach { assertTrue(it, WikipediaRef.isWikipediaPageUrl(it)) }
    }

    @Test
    fun `rejects look-alike, userinfo, port, fragment-host and non-https urls`() {
        listOf(
            "https://evil.com/.wikipedia.org/x",
            "https://evil.com?.wikipedia.org/",
            "https://evil.com#.wikipedia.org/",
            "https://en.wikipedia.org.evil.com/wiki/X",
            "https://u@en.wikipedia.org/wiki/X",
            "https://en.wikipedia.org:8443/wiki/X",
            "https://en.wikipedia.org./wiki/X",
            "http://en.wikipedia.org/wiki/X",
            "javascript:alert(1)",
            "intent://en.wikipedia.org/#Intent;end",
            "",
            null,
        ).forEach { assertFalse(it.toString(), WikipediaRef.isWikipediaPageUrl(it)) }
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

    /** "lat,lon" pairs of the track query, in order. */
    private fun coords(q: String): List<Pair<String, String>> =
        q.substringAfter("around:${OverpassQl.TRACK_QUERY_RADIUS_M},").substringBefore(")").split(",").chunked(2) { it[0] to it[1] }

    /** 11 km north from Red Square, a point every ~11 m. */
    private val track = List(1000) { i -> (55.75421 + i * 0.0001) to 37.61961 }

    @Test
    fun `track query contains only cell centers`() {
        val q = OverpassQl.aroundTrackCells(TrackQuery.cells(track))!!
        val center = Regex("""^-?\d+\.\d{2}(25|75)$""")
        for ((lat, lon) in coords(q)) {
            assertTrue(lat, center.matches(lat))
            assertTrue(lon, center.matches(lon))
        }
        assertTrue(q, q.endsWith("out center ${OverpassQl.MAX_TRACK_RESULTS};"))
    }

    @Test
    fun `track query never contains the raw start or finish`() {
        val q = OverpassQl.aroundTrackCells(TrackQuery.cells(track))!!
        assertFalse(q, q.contains("55.7542,37.6196"))
        assertFalse(q, q.contains("55.8541,37.6196"))
        // The first cell sent is the one of the first point 300 m along the track, not the start cell.
        assertTrue(coords(q).first() != ("55.7525" to "37.6175"))
    }

    @Test
    fun `single cell gives a point query`() {
        val q = OverpassQl.aroundTrackCells(listOf(GeoCell.of(55.7558, 37.6173)))!!
        assertTrue(q, q.contains("(around:800,55.7575,37.6175)"))
    }

    @Test
    fun `long cell path is capped at 80 vertices and keeps both ends`() {
        val cells = List(500) { GeoCell(11000 + it, 7400) }
        val c = coords(OverpassQl.aroundTrackCells(cells)!!)
        assertEquals(OverpassQl.MAX_POLYLINE_VERTICES, c.size)
        assertEquals("55.0025" to "37.0025", c.first())
        assertEquals("57.4975" to "37.0025", c.last())
    }

    @Test
    fun `query radius covers track radius plus half a cell diagonal`() {
        val halfDiagonal = Geo.distanceMeters(0.0, 0.0, GeoCell.SIZE_DEG / 2, GeoCell.SIZE_DEG / 2)
        assertTrue(OverpassQl.TRACK_QUERY_RADIUS_M >= OverpassQl.TRACK_RADIUS_M + halfDiagonal)
    }

    @Test
    fun `no cells, no query`() {
        assertNull(OverpassQl.aroundTrackCells(emptyList()))
    }

    @Test
    fun `simplify keeps endpoints and size`() {
        val points = List(11) { i -> i.toDouble() to 0.0 }
        val s = OverpassQl.simplify(points, 5)
        assertEquals(listOf(0.0, 2.5, 5.0, 7.5, 10.0).map { Math.round(it).toDouble() }, s.map { it.first })
        assertEquals(points, OverpassQl.simplify(points, 11))
    }
}

class TrackQueryTest {
    private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>) = Geo.distanceMeters(a.first, a.second, b.first, b.second)

    /** Straight north from (55.0, 37.0), a point every ~11.1 m. */
    private fun north(n: Int) = List(n) { i -> (55.0 + i * 0.0001) to 37.0 }

    @Test
    fun `drops the first and last 300 m of path`() {
        val track = north(1000)
        val inner = TrackQuery.interior(track)
        assertTrue(inner.isNotEmpty())
        assertTrue(inner.all { dist(it, track.first()) >= TrackQuery.END_TRIM_M && dist(it, track.last()) >= TrackQuery.END_TRIM_M })
        assertEquals(TrackQuery.END_TRIM_M, dist(inner.first(), track.first()), 12.0)
        assertEquals(TrackQuery.END_TRIM_M, dist(inner.last(), track.last()), 12.0)
    }

    @Test
    fun `drops points within 300 m of start or finish even mid-track`() {
        // 2 km east, then back west through the start and 2 km beyond it (~6.4 m per step at 55° N).
        val east = List(313) { i -> 55.0 to 37.0 + i * 0.0001 }
        val west = List(626) { i -> 55.0 to 37.0312 - i * 0.0001 }
        val track = east + west
        val start = track.first()
        val inner = TrackQuery.interior(track)
        assertTrue(inner.isNotEmpty())
        assertTrue(inner.none { dist(it, start) < TrackQuery.END_TRIM_M })
    }

    @Test
    fun `short track falls back to the point at half its length`() {
        val track = north(38) // ~410 m: nothing survives a 300 m trim from both ends; half = 205.7 m
        assertTrue(TrackQuery.interior(track).isEmpty())
        val half = TrackQuery.pointAtHalfLength(track)
        assertEquals(track[19], half) // first point at >= 205.7 m: 19 x 11.12 m
        assertEquals(listOf(GeoCell.of(half.first, half.second)), TrackQuery.cells(track))
    }

    @Test
    fun `standing still yields a single cell`() {
        val track = List(50) { 55.7558 to 37.6173 }
        assertEquals(listOf(GeoCell.of(55.7558, 37.6173)), TrackQuery.cells(track))
        assertEquals(listOf(GeoCell.of(55.7558, 37.6173)), TrackQuery.cells(track.take(1)))
        assertTrue(TrackQuery.cells(emptyList()).isEmpty())
    }

    @Test
    fun `snaps to cell centers and merges consecutive duplicates`() {
        val outAndBack = north(1000) + north(1000).reversed()
        val cells = TrackQuery.cells(outAndBack)
        assertTrue(cells.zipWithNext().none { (a, b) -> a == b })
        // Path order is kept: the way back revisits the same cells instead of being dropped.
        assertTrue(cells.size > cells.toSet().size)
        assertTrue(cells.all { it.col == GeoCell.of(55.0, 37.0).col })
    }

    @Test
    fun `filter line stays within a step of the track and is bounded`() {
        val dense = north(1000)
        val thin = TrackQuery.filterLine(dense)
        assertEquals(dense.first(), thin.first())
        assertEquals(dense.last(), thin.last())
        assertTrue(thin.size < dense.size)
        assertTrue(dense.all { p -> PoiProximity.distanceToLine(p.first, p.second, thin) <= 25.0 })

        val long = List(100_000) { i -> (40.0 + i * 0.0001) to 37.0 } // ~1 110 km
        assertTrue(TrackQuery.filterLine(long).size <= TrackQuery.FILTER_MAX_VERTICES + 2)
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

    // ~63.8 m per 0.001° of longitude at 55° N; the line runs 1.1 km north along 37.0.
    private val line = listOf(55.0 to 37.0, 55.0100 to 37.0)
    private fun east(id: String, lat: Double, meters: Double) = poi(id, lat, 37.0 + meters / 63_800.0)

    @Test
    fun `alongLine drops places farther than 400 m`() {
        val inside = east("inside", 55.005, 300.0)
        val outside = east("outside", 55.005, 500.0)
        assertEquals(listOf(inside), PoiProximity.alongLine(listOf(outside, inside), line, limit = 10))
    }

    @Test
    fun `alongLine measures to segments, not vertices`() {
        // 550 m from both vertices, 100 m from the segment between them.
        val mid = east("mid", 55.005, 100.0)
        assertEquals(listOf(mid), PoiProximity.alongLine(listOf(mid), line, limit = 10))
        assertEquals(100.0, PoiProximity.distanceToLine(mid.lat, mid.lon, line), 2.0)
    }

    @Test
    fun `alongLine ranks nearest first and limits`() {
        val a = east("a", 55.002, 250.0)
        val b = east("b", 55.004, 50.0)
        val c = east("c", 55.006, 150.0)
        assertEquals(listOf(b, c), PoiProximity.alongLine(listOf(a, b, c), line, limit = 2))
    }

    @Test
    fun `empty line yields nothing`() {
        assertTrue(PoiProximity.alongLine(listOf(near), emptyList(), limit = 10).isEmpty())
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
