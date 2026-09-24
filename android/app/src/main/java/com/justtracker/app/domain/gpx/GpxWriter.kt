package com.justtracker.app.domain.gpx

import com.justtracker.app.domain.model.ActivityType
import com.justtracker.app.domain.model.Track
import com.justtracker.app.domain.model.TrackPoint
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Streams a GPX 1.1 document that validates against the official schema (docs/06_system_analysis.md §5):
 * one `<trkseg>` per recording segment, `<bounds>` in the metadata, times in UTC — with milliseconds when
 * the fix has them, so fixes less than a second apart never share a timestamp. GPX 1.1 has no speed or
 * course elements and accepts only foreign-namespace content inside `<extensions>`, so per-point speed
 * (m/s) and course (degrees true) go into Garmin's TrackPointExtension v2, which Garmin Connect, Strava,
 * OsmAnd, GPXSee and others read. `<ele>` is the receiver's height above the WGS84 ellipsoid.
 */
object GpxWriter {
    const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
    const val TRACK_POINT_EXTENSION_NAMESPACE = "http://www.garmin.com/xmlschemas/TrackPointExtension/v2"
    private const val SCHEMA_LOCATION = "$GPX_NAMESPACE http://www.topografix.com/GPX/1/1/gpx.xsd " +
        "$TRACK_POINT_EXTENSION_NAMESPACE https://www8.garmin.com/xmlschemas/TrackPointExtensionv2.xsd"

    private val SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).withZone(ZoneOffset.UTC)
    private val MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).withZone(ZoneOffset.UTC)

    fun write(track: Track, points: List<TrackPoint>, out: Appendable) {
        // The schema only knows latitudes in [-90, 90] and longitudes in [-180, 180).
        val valid = points.filter { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"JustTracker\"")
            .append(" xmlns=\"").append(GPX_NAMESPACE).append('"')
            .append(" xmlns:gpxtpx=\"").append(TRACK_POINT_EXTENSION_NAMESPACE).append('"')
            .append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
            .append(" xsi:schemaLocation=\"").append(SCHEMA_LOCATION).append("\">\n")
        out.append("  <metadata>\n")
        out.append("    <name>").append(escape(track.name)).append("</name>\n")
        out.append("    <time>").append(iso(track.startedAt)).append("</time>\n")
        if (valid.isNotEmpty()) {
            out.append("    <bounds minlat=\"").append(latitude(valid.minOf { it.lat }))
                .append("\" minlon=\"").append(longitude(valid.minOf { it.lon }))
                .append("\" maxlat=\"").append(latitude(valid.maxOf { it.lat }))
                .append("\" maxlon=\"").append(longitude(valid.maxOf { it.lon })).append("\"/>\n")
        }
        out.append("  </metadata>\n")
        out.append("  <trk>\n    <name>").append(escape(track.name)).append("</name>\n")
        out.append("    <type>").append(gpxType(track.activityType)).append("</type>\n")
        var currentSegment: Int? = null
        for (p in valid) {
            if (p.segment != currentSegment) {
                if (currentSegment != null) out.append("    </trkseg>\n")
                out.append("    <trkseg>\n")
                currentSegment = p.segment
            }
            out.append("      <trkpt lat=\"").append(latitude(p.lat)).append("\" lon=\"").append(longitude(p.lon)).append("\">\n")
            p.altitudeM?.takeIf { it.isFinite() }?.let {
                out.append("        <ele>").append(String.format(Locale.US, "%.1f", it)).append("</ele>\n")
            }
            out.append("        <time>").append(iso(p.timestamp)).append("</time>\n")
            val speed = p.speedMps?.takeIf { it.isFinite() && it >= 0f }
            val course = p.bearingDeg?.takeIf { it.isFinite() }
            if (speed != null || course != null) {
                // Element order is fixed by the extension schema: speed, then course.
                out.append("        <extensions><gpxtpx:TrackPointExtension>")
                speed?.let { out.append("<gpxtpx:speed>").append(String.format(Locale.US, "%.2f", it)).append("</gpxtpx:speed>") }
                course?.let { out.append("<gpxtpx:course>").append(String.format(Locale.US, "%.1f", course(it))).append("</gpxtpx:course>") }
                out.append("</gpxtpx:TrackPointExtension></extensions>\n")
            }
            out.append("      </trkpt>\n")
        }
        if (currentSegment != null) out.append("    </trkseg>\n")
        out.append("  </trk>\n</gpx>\n")
    }

    fun gpxType(type: ActivityType): String = when (type) {
        ActivityType.WALK -> "walking"
        ActivityType.RUN -> "running"
        ActivityType.BIKE -> "cycling"
        ActivityType.CAR -> "driving"
        ActivityType.OTHER, ActivityType.UNKNOWN -> "other"
    }

    /**
     * Safe, readable file name: letters of any script and digits are kept (Cyrillic names stay readable),
     * every run of anything else — spaces, punctuation, path separators, control and bidi characters —
     * becomes one "_"; the track id keeps names of equally named tracks apart.
     */
    fun fileName(track: Track): String {
        val base = track.name.replace(Regex("[^\\p{L}\\p{M}\\p{N}_-]+"), "_").trim('_').take(60)
            .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
            .trimEnd('_').ifEmpty { "track" }
        return "${base}_${track.id}.gpx"
    }

    /** UTC; milliseconds only when the fix has them. */
    internal fun iso(epochMs: Long): String =
        (if (Math.floorMod(epochMs, 1000L) == 0L) SECONDS else MILLIS).format(Instant.ofEpochMilli(epochMs))

    /** 6 decimals ≈ 0.1 m. */
    private fun latitude(value: Double) = String.format(Locale.US, "%.6f", value)

    /** 6 decimals; 180° is written as -180° (the same meridian) because the schema excludes 180. */
    private fun longitude(value: Double): String {
        val rounded = Math.round(value * 1e6) / 1e6
        return String.format(Locale.US, "%.6f", if (rounded >= 180.0) rounded - 360.0 else rounded)
    }

    /** Course in [0, 360) at the written precision of 0.1° (359.96° is written as 0.0°, not 360.0°). */
    private fun course(value: Float): Float {
        val tenths = Math.round((((value % 360f) + 360f) % 360f) * 10f) / 10f
        return if (tenths >= 360f) 0f else tenths
    }

    /** Escapes markup and drops characters XML 1.0 cannot carry at all (controls, unpaired surrogates). */
    internal fun escape(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when (cp) {
                '&'.code -> append("&amp;")
                '<'.code -> append("&lt;")
                '>'.code -> append("&gt;")
                '"'.code -> append("&quot;")
                '\''.code -> append("&apos;")
                else -> if (isXmlChar(cp)) appendCodePoint(cp)
            }
        }
    }

    /** The XML 1.0 `Char` production. */
    private fun isXmlChar(cp: Int) =
        cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF
}
