package com.justtracker.app.data.poi

import com.justtracker.app.util.AppUserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Minimal HTTPS client on top of HttpURLConnection — the POI feature does not justify OkHttp (ADR-08).
 * Every URL, including each redirect target, must pass [HostPolicy] (HTTPS, Overpass or Wikipedia only;
 * ADR-22): redirects are followed by hand for GET (Wikipedia redirects renamed titles) and refused for POST.
 * The network security config forbids cleartext anyway.
 */
class Http(private val userAgent: String = AppUserAgent.value) {
    class HttpException(val code: Int, message: String) : IOException(message)

    suspend fun get(url: String, accept: String = "application/json"): String = withContext(Dispatchers.IO) {
        HostPolicy.followRedirects(url) { current ->
            open(current, "GET", accept).use { conn ->
                if (conn.responseCode in REDIRECT_CODES) {
                    HostPolicy.Hop.Redirect(conn.getHeaderField("Location"))
                } else {
                    HostPolicy.Hop.Done(read(conn))
                }
            }
        }
    }

    /** A redirect answer is an [HttpException]: the form body is never re-sent anywhere else. */
    suspend fun postForm(url: String, form: String, accept: String = "application/json"): String = withContext(Dispatchers.IO) {
        open(url, "POST", accept).use { conn ->
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            read(conn)
        }
    }

    private fun open(url: String, method: String, accept: String): HttpURLConnection {
        if (!HostPolicy.isAllowed(url)) throw IOException("Host not allowed")
        return (URL(URI(url).toASCIIString()).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", accept)
        }
    }

    private fun read(conn: HttpURLConnection): String {
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.errorStream?.close()
            throw HttpException(code, "HTTP $code")
        }
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val sb = StringBuilder()
            val buf = CharArray(8 * 1024)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                sb.appendRange(buf, 0, n)
                if (sb.length > MAX_BODY_CHARS) throw IOException("Response too large")
            }
            return sb.toString()
        }
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T = try {
        block(this)
    } finally {
        disconnect()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 25_000
        private const val MAX_BODY_CHARS = 2 * 1024 * 1024
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
