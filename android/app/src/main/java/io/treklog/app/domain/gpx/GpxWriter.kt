package io.treklog.app.domain.gpx

import io.treklog.app.domain.model.ActivityType
import io.treklog.app.domain.model.Track
import io.treklog.app.domain.model.TrackPoint
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Streams a GPX 1.1 document (docs/06_system_analysis.md §5). One <trkseg> per segment. */
object GpxWriter {
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).withZone(ZoneOffset.UTC)

    fun write(track: Track, points: List<TrackPoint>, out: Appendable) {
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"TrekLog\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        out.append("  <metadata><name>").append(escape(track.name)).append("</name><time>")
            .append(iso(track.startedAt)).append("</time></metadata>\n")
        out.append("  <trk>\n    <name>").append(escape(track.name)).append("</name>\n")
        out.append("    <type>").append(gpxType(track.activityType)).append("</type>\n")
        var currentSegment: Int? = null
        for (p in points) {
            if (p.segment != currentSegment) {
                if (currentSegment != null) out.append("    </trkseg>\n")
                out.append("    <trkseg>\n")
                currentSegment = p.segment
            }
            out.append("      <trkpt lat=\"").append(coord(p.lat)).append("\" lon=\"").append(coord(p.lon)).append("\">\n")
            p.altitudeM?.let { out.append("        <ele>").append(String.format(Locale.US, "%.1f", it)).append("</ele>\n") }
            out.append("        <time>").append(iso(p.timestamp)).append("</time>\n")
            p.speedMps?.let {
                out.append("        <extensions><speed>").append(String.format(Locale.US, "%.2f", it)).append("</speed></extensions>\n")
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

    /** Safe file name: keeps ASCII letters/digits/._- only. */
    fun fileName(track: Track): String {
        val base = track.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "track" }
        return "${base.take(60)}_${track.id}.gpx"
    }

    private fun coord(v: Double) = String.format(Locale.US, "%.6f", v)
    private fun iso(epochMs: Long) = ISO.format(Instant.ofEpochMilli(epochMs))

    internal fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}
