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
import javax.xml.parsers.DocumentBuilderFactory

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
        TrackPoint(trackId = 7, segment = 0, timestamp = 1_758_276_000_000L, lat = 55.75, lon = 37.61, altitudeM = 150.24, accuracyM = 5f, speedMps = 1.4f, bearingDeg = null),
        TrackPoint(trackId = 7, segment = 0, timestamp = 1_758_276_001_000L, lat = 55.750012, lon = 37.61, altitudeM = null, accuracyM = 5f, speedMps = null, bearingDeg = null),
        TrackPoint(trackId = 7, segment = 1, timestamp = 1_758_276_050_000L, lat = 55.7501, lon = 37.6101, altitudeM = 151.0, accuracyM = 5f, speedMps = 1.5f, bearingDeg = null),
    )

    private fun render(): String = StringBuilder().also { GpxWriter.write(track, points, it) }.toString()

    @Test
    fun `produces well-formed XML with escaped name`() {
        val xml = render()
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(xml.byteInputStream())
        assertEquals("gpx", doc.documentElement.localName)
        assertEquals("Morning <walk> & \"test\"", doc.getElementsByTagNameNS("*", "name").item(0).textContent)
        assertTrue(xml.contains("&lt;walk&gt; &amp; &quot;test&quot;"))
    }

    @Test
    fun `one trkseg per segment and correct point count`() {
        val xml = render()
        assertEquals(2, Regex("<trkseg>").findAll(xml).count())
        assertEquals(3, Regex("<trkpt ").findAll(xml).count())
    }

    @Test
    fun `iso timestamps and optional elements`() {
        val xml = render()
        assertTrue(xml.contains("<time>2025-09-19T10:00:00Z</time>"))
        assertTrue(xml.contains("<ele>150.2</ele>"))
        assertTrue(xml.contains("<type>walking</type>"))
        assertTrue(xml.contains("lat=\"55.750012\""))
        // second point has neither altitude nor speed
        val second = xml.substringAfter("lat=\"55.750012\"").substringBefore("</trkpt>")
        assertFalse(second.contains("<ele>"))
        assertFalse(second.contains("<speed>"))
    }

    @Test
    fun `file name is sanitised`() {
        assertEquals("Morning_walk_test_7.gpx", GpxWriter.fileName(track))
        assertEquals("track_1.gpx", GpxWriter.fileName(track.copy(id = 1, name = "Прогулка")))
    }
}
