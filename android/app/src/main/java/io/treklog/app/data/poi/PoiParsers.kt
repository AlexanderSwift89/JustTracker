package io.treklog.app.data.poi

import io.treklog.app.domain.poi.Poi
import io.treklog.app.domain.poi.PoiFactory
import io.treklog.app.domain.poi.PoiSummary
import io.treklog.app.domain.poi.WikipediaRef
import org.json.JSONException
import org.json.JSONObject

/** Overpass `[out:json]` → list of [Poi]. Malformed elements are skipped, never thrown. */
object OverpassParser {
    @Throws(JSONException::class)
    fun parse(json: String, preferredLang: String): List<Poi> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val result = ArrayList<Poi>(elements.length())
        val seenKeys = HashSet<String>()
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val type = el.optString("type")
            val id = el.optLong("id", -1)
            if (id < 0 || type !in OSM_TYPES) continue
            val (lat, lon) = coordinates(el) ?: continue
            val tagsObj = el.optJSONObject("tags") ?: continue
            val tags = HashMap<String, String>(tagsObj.length())
            for (key in tagsObj.keys()) tags[key] = tagsObj.optString(key)
            val poi = PoiFactory.fromTags(type, id, lat, lon, tags, preferredLang) ?: continue
            // A building outline (way) and its entrance (node) often carry the same article: keep the first.
            if (seenKeys.add(poi.wikipedia.key)) result.add(poi)
        }
        return result
    }

    private fun coordinates(el: JSONObject): Pair<Double, Double>? {
        val src = if (el.has("lat")) el else el.optJSONObject("center") ?: return null
        val lat = src.optDouble("lat", Double.NaN)
        val lon = src.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN() || lat < -90 || lat > 90 || lon < -180 || lon > 180) return null
        return lat to lon
    }

    private val OSM_TYPES = setOf("node", "way", "relation")
}

/** Wikipedia REST `page/summary` → [PoiSummary]; null for disambiguation pages or missing extract. */
object WikiSummaryParser {
    @Throws(JSONException::class)
    fun parse(json: String, ref: WikipediaRef): PoiSummary? {
        val obj = JSONObject(json)
        if (obj.optString("type") == "disambiguation") return null
        val extract = obj.optString("extract").trim()
        if (extract.isEmpty()) return null
        val title = obj.optString("title").ifBlank { ref.title }
        val lang = obj.optString("lang").takeIf { WikipediaRef.LANG_PATTERN.matches(it) } ?: ref.lang
        val url = obj.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page")
            ?.takeIf { it.startsWith("https://") && it.contains(".wikipedia.org/") }
            ?: ref.pageUrl
        return PoiSummary(title = title, extract = extract, lang = lang, pageUrl = url)
    }
}
