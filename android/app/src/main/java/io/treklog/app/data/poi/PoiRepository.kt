package io.treklog.app.data.poi

import android.os.SystemClock
import io.treklog.app.domain.poi.GeoCell
import io.treklog.app.domain.poi.OverpassQl
import io.treklog.app.domain.poi.Poi
import io.treklog.app.domain.poi.PoiProximity
import io.treklog.app.domain.poi.PoiSummary
import io.treklog.app.domain.poi.WikipediaRef
import io.treklog.app.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.util.Locale

/** Outcome of a POI lookup; [Unavailable] covers offline, rate-limited and server errors alike. */
sealed class PoiResult {
    data class Found(val pois: List<Poi>) : PoiResult()
    object Unavailable : PoiResult()
}

/**
 * In-memory cache + politeness layer over Overpass and Wikipedia (docs/05_architecture.md §11).
 *
 * - Overpass calls are serialized and spaced by at least [MIN_GAP_MS]; after a failure nothing is
 *   sent for [BACKOFF_MS] (the public instance answers 429/504 under load).
 * - Results are cached per [GeoCell] / per track for [CELL_TTL_MS]; summaries for the process lifetime.
 * - No coordinates are logged (AppLog.geo is debug-only).
 */
class PoiRepository(
    private val http: Http = Http(),
    private val overpassUrl: String = OVERPASS_URL,
    private val preferredLang: () -> String = { Locale.getDefault().language },
) {
    private class Entry(val pois: List<Poi>, val at: Long)

    private val cellCache = LruMap<GeoCell, Entry>(32)
    private val trackCache = LruMap<Long, Entry>(8)
    private val summaryCache = LruMap<String, PoiSummary?>(64)
    private val overpassLock = Mutex()
    private var lastOverpassAt = 0L
    private var failedUntil = 0L

    /** Cached places for the cell, if fresh — used by the announcer without triggering network. */
    fun cachedAround(cell: GeoCell): List<Poi>? = synchronized(cellCache) { cellCache[cell]?.takeIf { fresh(it) }?.pois }

    suspend fun aroundCell(cell: GeoCell): PoiResult {
        cachedAround(cell)?.let { return PoiResult.Found(it) }
        return overpass(OverpassQl.aroundCell(cell))
            ?.let { PoiProximity.nearest(it, cell.centerLat, cell.centerLon, OverpassQl.MAX_AROUND_CELL) }
            ?.also { pois -> synchronized(cellCache) { cellCache[cell] = Entry(pois, SystemClock.elapsedRealtime()) } }
            ?.let { PoiResult.Found(it) } ?: PoiResult.Unavailable
    }

    suspend fun aroundTrack(trackId: Long, points: List<Pair<Double, Double>>): PoiResult {
        synchronized(trackCache) { trackCache[trackId]?.takeIf { fresh(it) } }?.let { return PoiResult.Found(it.pois) }
        val line = OverpassQl.simplify(points, OverpassQl.MAX_POLYLINE_VERTICES)
        val query = OverpassQl.aroundPolyline(line) ?: return PoiResult.Found(emptyList())
        return overpass(query)
            ?.let { PoiProximity.nearestToLine(it, line, OverpassQl.MAX_ALONG_TRACK) }
            ?.also { pois -> synchronized(trackCache) { trackCache[trackId] = Entry(pois, SystemClock.elapsedRealtime()) } }
            ?.let { PoiResult.Found(it) } ?: PoiResult.Unavailable
    }

    /** Null when the article has no usable summary (missing, disambiguation, offline). */
    suspend fun summary(ref: WikipediaRef): PoiSummary? {
        synchronized(summaryCache) { if (summaryCache.containsKey(ref.key)) return summaryCache[ref.key] }
        val summary = try {
            WikiSummaryParser.parse(http.get(ref.summaryUrl), ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("Wikipedia summary failed: ${e.javaClass.simpleName}")
            // Do not cache transient failures; a 404 is final for this article.
            if ((e as? Http.HttpException)?.code == 404) synchronized(summaryCache) { summaryCache[ref.key] = null }
            return null
        }
        synchronized(summaryCache) { summaryCache[ref.key] = summary }
        return summary
    }

    private suspend fun overpass(query: String): List<Poi>? = overpassLock.withLock {
        val now = SystemClock.elapsedRealtime()
        if (now < failedUntil) return null
        val wait = lastOverpassAt + MIN_GAP_MS - now
        if (wait > 0) delay(wait)
        lastOverpassAt = SystemClock.elapsedRealtime()
        try {
            val body = "data=" + URLEncoder.encode(query, "UTF-8")
            val json = http.postForm(overpassUrl, body)
            OverpassParser.parse(json, preferredLang()).also { AppLog.d("Overpass: ${it.size} places") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("Overpass failed: ${e.javaClass.simpleName} ${e.message}")
            failedUntil = SystemClock.elapsedRealtime() + BACKOFF_MS
            null
        }
    }

    private fun fresh(e: Entry) = SystemClock.elapsedRealtime() - e.at < CELL_TTL_MS

    private class LruMap<K, V>(private val max: Int) : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
    }

    companion object {
        const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
        const val MIN_GAP_MS = 15_000L
        const val BACKOFF_MS = 60_000L
        const val CELL_TTL_MS = 30 * 60_000L
    }
}
