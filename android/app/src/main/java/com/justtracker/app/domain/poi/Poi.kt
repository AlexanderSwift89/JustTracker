package com.justtracker.app.domain.poi

/** Coarse category derived from OSM tags; drives the label in the card (docs/06_system_analysis.md §3.7). */
enum class PoiKind { MUSEUM, ATTRACTION, HISTORIC, WORSHIP, NATURE, PLACE }

/**
 * Reference to a Wikipedia article parsed from the OSM `wikipedia=lang:Title` tag.
 * [lang] is validated against [LANG_PATTERN] because it becomes part of a hostname.
 */
data class WikipediaRef(val lang: String, val title: String) {
    /** Article URL on the mobile site (used for "Open in Wikipedia"). */
    val pageUrl: String get() = "https://$lang.m.wikipedia.org/wiki/${encodeTitle(title)}"

    /** REST summary endpoint. */
    val summaryUrl: String get() = "https://$lang.wikipedia.org/api/rest_v1/page/summary/${encodeTitle(title)}"

    /** Cache key: same article regardless of spaces vs underscores. */
    val key: String get() = "$lang:${title.replace(' ', '_')}"

    companion object {
        /** ISO-639 code, optionally with a script/region suffix (`zh-yue`, `be-tarask`). */
        val LANG_PATTERN = Regex("^[a-z]{2,3}(-[a-z]{2,10})?$")

        /** Parses `lang:Title`; returns null for malformed or unsafe values. */
        fun parse(tag: String?): WikipediaRef? {
            if (tag.isNullOrBlank()) return null
            val idx = tag.indexOf(':')
            if (idx <= 0) return null
            val lang = tag.substring(0, idx).trim().lowercase()
            val title = tag.substring(idx + 1).trim().replace('_', ' ')
            if (!LANG_PATTERN.matches(lang) || title.isEmpty() || title.length > 255) return null
            if (title.any { it == '#' || it == '?' || it < ' ' }) return null
            return WikipediaRef(lang, title)
        }

        /** Percent-encodes a title as a single path segment; spaces become underscores as Wikipedia expects. */
        fun encodeTitle(title: String): String {
            val sb = StringBuilder()
            for (b in title.replace(' ', '_').toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (ch.isLetterOrDigit() && c < 128 || ch == '_' || ch == '-' || ch == '.' || ch == '~' || ch == '(' || ch == ')' || ch == ',') {
                    sb.append(ch)
                } else {
                    sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
                }
            }
            return sb.toString()
        }

        private const val HEX = "0123456789ABCDEF"
    }
}

/** A point of interest near the user or the track. Coordinates WGS84. */
data class Poi(
    /** Stable id: `<osm type>/<osm id>` — used for marker diffing and "announced" bookkeeping. */
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val kind: PoiKind,
    val wikipedia: WikipediaRef,
)

/** Lead-section summary of a Wikipedia article. */
data class PoiSummary(
    val title: String,
    val extract: String,
    val lang: String,
    val pageUrl: String,
) {
    /** Short spoken form for automatic announcements: the first sentences up to ~[maxChars]. */
    fun spokenIntro(maxChars: Int = 320): String {
        if (extract.length <= maxChars) return extract
        val cut = extract.substring(0, maxChars)
        // Prefer a sentence boundary unless it would leave only a fragment (< 1/4 of the budget).
        val end = listOf(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? ")).max()
        return if (end > maxChars / 4) cut.substring(0, end + 1) else cut.trimEnd() + "…"
    }
}
