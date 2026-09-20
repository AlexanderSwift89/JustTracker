package com.justtracker.app.data.maps

import android.content.Context
import com.justtracker.app.domain.maps.LatLonBox
import com.justtracker.app.domain.model.AppLanguage
import com.justtracker.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI

/** One downloadable region from the bundled catalogue (`assets/maps/regions.json`). */
data class CatalogRegion(
    val id: String,
    val nameEn: String,
    val nameRu: String,
    val url: String,
    val sizeBytes: Long,
    /** Approximate; the `.map` header is authoritative once downloaded. */
    val box: LatLonBox,
) {
    fun name(language: AppLanguage): String = when (language) {
        AppLanguage.RU -> nameRu.ifBlank { nameEn }
        AppLanguage.EN -> nameEn.ifBlank { nameRu }
    }
}

/**
 * Parses the catalogue defensively: a malformed entry is skipped, never fatal. Only HTTPS URLs on
 * [ALLOWED_HOSTS] are accepted (docs/07_security.md §3 — every network host is whitelisted).
 */
object RegionCatalogParser {
    val ALLOWED_HOSTS = setOf("download.mapsforge.org")

    fun parse(json: String): List<CatalogRegion> {
        val root = runCatching { JSONObject(json) }.getOrElse { return emptyList() }
        val array = root.optJSONArray("regions") ?: return emptyList()
        val out = ArrayList<CatalogRegion>(array.length())
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val region = parseEntry(entry) ?: continue
            if (seen.add(region.id)) out += region
        }
        return out
    }

    private fun parseEntry(o: JSONObject): CatalogRegion? {
        val id = o.optString("id").trim()
        if (id.isEmpty() || !id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        val names = o.optJSONObject("name") ?: return null
        val nameEn = names.optString("en").trim()
        val nameRu = names.optString("ru").trim()
        if (nameEn.isEmpty() && nameRu.isEmpty()) return null
        val url = o.optString("url").trim()
        if (!isAllowedUrl(url)) return null
        val size = o.optLong("sizeBytes", -1L)
        if (size <= 0) return null
        val bbox = o.optJSONArray("bbox") ?: return null
        if (bbox.length() != 4) return null
        val box = runCatching {
            LatLonBox(bbox.getDouble(0), bbox.getDouble(1), bbox.getDouble(2), bbox.getDouble(3))
        }.getOrNull() ?: return null
        return CatalogRegion(id, nameEn, nameRu, url, size, box)
    }

    fun isAllowedUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host in ALLOWED_HOSTS && uri.path.endsWith(".map")
    }
}

/** Loads the bundled catalogue once per process. */
class RegionCatalog(private val context: Context) {
    @Volatile
    private var cached: List<CatalogRegion>? = null

    suspend fun load(): List<CatalogRegion> = cached ?: withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        }.map(RegionCatalogParser::parse)
            .onFailure { AppLog.e("Region catalogue unreadable", it) }
            .getOrDefault(emptyList())
            .also { cached = it }
    }

    suspend fun find(id: String): CatalogRegion? = load().firstOrNull { it.id == id }

    companion object {
        const val ASSET_PATH = "maps/regions.json"
    }
}
