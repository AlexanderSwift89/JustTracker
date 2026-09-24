package com.justtracker.app.domain

import com.justtracker.app.domain.gpx.GpxWriter
import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.TrackPoint
import com.justtracker.app.domain.model.TrackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

class GpxWriterTest {
    private val track = Track(
        id = 7,
        name = "Morning <walk> & \"test\"",
        status = TrackStatus.FINISHED,
        activityType = ActivityType.WALK,
        activityManual = false,
        startedAt = 1_758_276_000_000L, // 2025-09-19T10:00:00Z
        finishedAt = 1_758_276_060_000L,
    )

    private val points = listOf(
        TrackPoint(trackId = 7, segment = 0, timestamp = 1_758_276_000_000L, lat = 55.75, lon = 37.61, altitudeM = 150.24, accuracyM = 5f, speedMps = 1.4f, bearingDeg = 12.34f),
        TrackPoint(trackId = 7, segment = 0, timestamp = 1_758_276_000_500L, lat = 55.750012, lon = 37.61, altitudeM = null, accuracyM = 5f, speedMps = null, bearingDeg = null),
        TrackPoint(trackId = 7, segment = 1, timestamp = 1_758_276_050_000L, lat = 55.7501, lon = 37.6101, altitudeM = 151.0, accuracyM = 5f, speedMps = 1.5f, bearingDeg = 359.97f),
    )

    private fun render(t: Track = track, p: List<TrackPoint> = points): String = StringBuilder().also { GpxWriter.write(t, p, it) }.toString()

    private fun parse(xml: String): Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(xml.byteInputStream())

    /** Validates against the GPX 1.1 schema and Garmin's TrackPointExtension v2 (test subsets in resources/gpx). */
    private fun validate(xml: String) {
        val sources = listOf("/gpx/gpx-1.1-subset.xsd", "/gpx/trackpoint-extension-v2-subset.xsd")
            .map { StreamSource(javaClass.getResource(it)!!.toExternalForm()) }
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(sources.toTypedArray())
            .newValidator().validate(StreamSource(StringReader(xml)))
    }

    @Test
    fun `produces well-formed XML with escaped name`() {
        val xml = render()
        val doc = parse(xml)
        assertEquals("gpx", doc.documentElement.localName)
        assertEquals("Morning <walk> & \"test\"", doc.getElementsByTagNameNS(GpxWriter.GPX_NAMESPACE, "name").item(0).textContent)
        assertTrue(xml.contains("&lt;walk&gt; &amp; &quot;test&quot;"))
    }

    @Test
    fun `validates against the GPX 1_1 schema`() {
        validate(render())
    }

    @Test
    fun `speed without a namespace is rejected by the schema`() {
        // What 1.0.x wrote: <speed> in the GPX namespace inside <extensions> — the schema only allows other namespaces.
        val old = render().replace(Regex("<extensions>.*?</extensions>"), "<extensions><speed>1.40</speed></extensions>")
        val failure = runCatching { validate(old) }.exceptionOrNull()
        assertTrue("old format must not validate", failure is org.xml.sax.SAXException)
    }

    @Test
    fun `one trkseg per segment and correct point count`() {
        val xml = render()
        assertEquals(2, Regex("<trkseg>").findAll(xml).count())
        assertEquals(3, Regex("<trkpt ").findAll(xml).count())
    }

    @Test
    fun `iso timestamps keep milliseconds only when present`() {
        val xml = render()
        assertTrue(xml.contains("<time>2025-09-19T10:00:00Z</time>"))
        assertTrue(xml.contains("<time>2025-09-19T10:00:00.500Z</time>"))
        assertEquals("2025-09-19T10:00:50Z", GpxWriter.iso(1_758_276_050_000L))
        assertEquals("2025-09-19T10:00:50.007Z", GpxWriter.iso(1_758_276_050_007L))
    }

    @Test
    fun `optional elements, speed and course in the Garmin extension`() {
        val xml = render()
        assertTrue(xml.contains("<ele>150.2</ele>"))
        assertTrue(xml.contains("<type>walking</type>"))
        assertTrue(xml.contains("lat=\"55.750012\""))
        assertTrue(xml.contains("<gpxtpx:speed>1.40</gpxtpx:speed><gpxtpx:course>12.3</gpxtpx:course>"))
        // 359.97° is 0.0° at the written precision, never 360.0
        assertTrue(xml.contains("<gpxtpx:course>0.0</gpxtpx:course>"))
        val doc = parse(xml)
        assertEquals(2, doc.getElementsByTagNameNS(GpxWriter.TRACK_POINT_EXTENSION_NAMESPACE, "speed").length)
        assertEquals(0, doc.getElementsByTagNameNS(GpxWriter.GPX_NAMESPACE, "speed").length)
        // second point has neither altitude nor speed nor course
        val second = xml.substringAfter("lat=\"55.750012\"").substringBefore("</trkpt>")
        assertFalse(second.contains("<ele>"))
        assertFalse(second.contains("<extensions>"))
    }

    @Test
    fun `metadata bounds cover all points`() {
        assertTrue(render().contains("<bounds minlat=\"55.750000\" minlon=\"37.610000\" maxlat=\"55.750100\" maxlon=\"37.610100\"/>"))
        assertFalse(render(p = emptyList()).contains("<bounds"))
        validate(render(p = emptyList()))
    }

    @Test
    fun `longitude 180 is written as -180`() {
        val edge = listOf(points[0].copy(lon = 180.0), points[0].copy(lon = 179.99999996, timestamp = points[0].timestamp + 1000))
        val xml = render(p = edge)
        assertFalse(xml.contains("\"180.000000\""))
        assertEquals(4, Regex("\"-180\\.000000\"").findAll(xml).count()) // both points, minlon and maxlon
        validate(xml)
    }

    @Test
    fun `characters XML cannot carry are dropped from the name`() {
        val odd = track.copy(name = "Run\u0001 \uD800 ok 😀")
        val xml = render(t = odd)
        assertEquals("Run  ok 😀", parse(xml).getElementsByTagNameNS(GpxWriter.GPX_NAMESPACE, "name").item(0).textContent)
        validate(xml)
    }

    @Test
    fun `non-finite values are not written`() {
        val bad = listOf(
            points[0].copy(altitudeM = Double.NaN, speedMps = Float.POSITIVE_INFINITY, bearingDeg = Float.NaN),
            points[0].copy(lat = Double.NaN, timestamp = points[0].timestamp + 1000),
        )
        val xml = render(p = bad)
        assertEquals(1, Regex("<trkpt ").findAll(xml).count())
        assertFalse(xml.contains("NaN") || xml.contains("Infinity"))
        validate(xml)
    }

    @Test
    fun `file name keeps letters of any script and drops the rest`() {
        assertEquals("Morning_walk_test_7.gpx", GpxWriter.fileName(track))
        assertEquals("Прогулка_1.gpx", GpxWriter.fileName(track.copy(id = 1, name = "Прогулка")))
        assertEquals("Бег_23_сент_14_33_4.gpx", GpxWriter.fileName(track.copy(id = 4, name = "Бег · 23 сент., 14:33")))
        assertEquals("etc_passwd_5.gpx", GpxWriter.fileName(track.copy(id = 5, name = "../../etc/passwd")))
        assertEquals("track_6.gpx", GpxWriter.fileName(track.copy(id = 6, name = "  ·· ")))
        assertEquals("evil_txt_8.gpx", GpxWriter.fileName(track.copy(id = 8, name = "evil‮txt")))
    }
}
