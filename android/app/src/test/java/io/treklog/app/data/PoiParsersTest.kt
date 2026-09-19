package io.treklog.app.data

import io.treklog.app.data.poi.OverpassParser
import io.treklog.app.data.poi.WikiSummaryParser
import io.treklog.app.domain.poi.PoiKind
import io.treklog.app.domain.poi.WikipediaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassParserTest {
    private val json = """
        {"version":0.6,"elements":[
          {"type":"node","id":1,"lat":55.7539,"lon":37.6208,
           "tags":{"name":"Saint Basil's Cathedral","name:ru":"Храм Василия Блаженного","wikipedia":"en:Saint Basil's Cathedral",
                   "wikipedia:ru":"Храм Василия Блаженного","amenity":"place_of_worship"}},
          {"type":"way","id":2,"center":{"lat":55.7520,"lon":37.6175},
           "tags":{"name":"GUM","wikipedia":"en:GUM (department store)","shop":"mall"}},
          {"type":"way","id":3,"center":{"lat":55.7520,"lon":37.6175},
           "tags":{"name":"GUM entrance","wikipedia":"en:GUM_(department_store)"}},
          {"type":"relation","id":4,"tags":{"name":"No coordinates","wikipedia":"en:X"}},
          {"type":"node","id":5,"lat":55.0,"lon":37.0,"tags":{"name":"No article"}},
          {"type":"node","id":6,"lat":55.0,"lon":37.0,"tags":{"name":"Evil","wikipedia":"evil.com/a:B"}},
          {"type":"node","id":7,"lat":95.0,"lon":37.0,"tags":{"name":"Bad lat","wikipedia":"en:Y"}}
        ]}
    """.trimIndent()

    @Test
    fun `parses nodes and way centers, skips invalid elements`() {
        val pois = OverpassParser.parse(json, "ru")
        assertEquals(listOf("node/1", "way/2"), pois.map { it.id })
    }

    @Test
    fun `uses localized name and article`() {
        val cathedral = OverpassParser.parse(json, "ru").first()
        assertEquals("Храм Василия Блаженного", cathedral.name)
        assertEquals(WikipediaRef("ru", "Храм Василия Блаженного"), cathedral.wikipedia)
        assertEquals(PoiKind.WORSHIP, cathedral.kind)
        assertEquals(55.7539, cathedral.lat, 1e-9)
    }

    @Test
    fun `falls back to default name for other locales`() {
        val cathedral = OverpassParser.parse(json, "de").first()
        assertEquals("Saint Basil's Cathedral", cathedral.name)
        assertEquals("en", cathedral.wikipedia.lang)
    }

    @Test
    fun `deduplicates elements pointing to the same article`() {
        // way/3 references the same article as way/2 (underscores vs spaces) and is dropped.
        assertTrue(OverpassParser.parse(json, "en").none { it.id == "way/3" })
    }

    @Test
    fun `empty and malformed payloads yield no places`() {
        assertEquals(emptyList<Any>(), OverpassParser.parse("{}", "en"))
        assertEquals(emptyList<Any>(), OverpassParser.parse("""{"elements":[{"type":"node"}]}""", "en"))
    }
}

class WikiSummaryParserTest {
    private val ref = WikipediaRef("en", "Red Square")

    @Test
    fun `parses standard summary`() {
        val json = """
            {"type":"standard","title":"Red Square","lang":"en","extract":"Red Square is a city square in Moscow.",
             "content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Red_Square"},
                             "mobile":{"page":"https://en.m.wikipedia.org/wiki/Red_Square"}}}
        """.trimIndent()
        val s = WikiSummaryParser.parse(json, ref)!!
        assertEquals("Red Square", s.title)
        assertEquals("Red Square is a city square in Moscow.", s.extract)
        assertEquals("en", s.lang)
        assertEquals("https://en.m.wikipedia.org/wiki/Red_Square", s.pageUrl)
    }

    @Test
    fun `disambiguation and empty extract yield null`() {
        assertNull(WikiSummaryParser.parse("""{"type":"disambiguation","extract":"May refer to"}""", ref))
        assertNull(WikiSummaryParser.parse("""{"type":"standard","extract":""}""", ref))
        assertNull(WikiSummaryParser.parse("""{}""", ref))
    }

    @Test
    fun `untrusted url and lang fall back to the reference`() {
        val json = """{"type":"standard","lang":"../x","extract":"Text","content_urls":{"mobile":{"page":"http://phish.example/wiki"}}}"""
        val s = WikiSummaryParser.parse(json, ref)!!
        assertEquals("en", s.lang)
        assertEquals(ref.pageUrl, s.pageUrl)
    }
}
