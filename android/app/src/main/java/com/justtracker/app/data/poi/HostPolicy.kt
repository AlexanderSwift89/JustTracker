package com.justtracker.app.data.poi

import com.justtracker.app.domain.poi.WikipediaRef
import java.io.IOException
import java.net.URI

/**
 * The network perimeter of [Http] (docs/07_security.md §3, ADR-22): HTTPS to Overpass and Wikipedia only, on the
 * default port, without credentials in the URL — checked for the first URL and again for every redirect hop.
 * Pure JVM code, so the rules are unit-tested.
 */
object HostPolicy {
    /** Hosts allowed besides `wikipedia.org` and its subdomains ([WikipediaRef.isWikipediaHost]). */
    val EXACT_HOSTS = setOf("overpass-api.de")

    /** Wikipedia's REST API redirects a normalised or renamed title once; more than this is a loop or an attack. */
    const val MAX_REDIRECTS = 3

    fun isAllowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) &&
            (host in EXACT_HOSTS || WikipediaRef.isWikipediaHost(host))
    }

    /** Absolute target of a redirect from [current] (`Location` may be relative), or null when missing, malformed or not allowed. */
    fun resolveRedirect(current: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        val target = runCatching { URI(current).resolve(location.trim()).toString() }.getOrNull() ?: return null
        return target.takeIf(::isAllowed)
    }

    /** Outcome of one request: the final value, or a redirect to follow. */
    sealed class Hop<out T> {
        data class Done<T>(val value: T) : Hop<T>()
        data class Redirect(val location: String?) : Hop<Nothing>()
    }

    /**
     * Runs [hop] for [start] and then for every redirect target, each re-checked by [resolveRedirect].
     * @throws IOException when a target is not allowed or after more than [maxHops] redirects.
     */
    fun <T> followRedirects(start: String, maxHops: Int = MAX_REDIRECTS, hop: (String) -> Hop<T>): T {
        var url = start
        var redirects = 0
        while (true) {
            when (val result = hop(url)) {
                is Hop.Done -> return result.value
                is Hop.Redirect -> {
                    if (++redirects > maxHops) throw IOException("Too many redirects")
                    url = resolveRedirect(url, result.location) ?: throw IOException("Redirect not allowed")
                }
            }
        }
    }
}
