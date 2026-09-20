package com.justtracker.app.data.poi

import com.justtracker.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTPS client on top of HttpURLConnection — the POI feature does not justify OkHttp
 * (ADR-08). Only `https` URLs are accepted; the network security config forbids cleartext anyway.
 */
class Http(private val userAgent: String = DEFAULT_USER_AGENT) {
    class HttpException(val code: Int, message: String) : IOException(message)

    suspend fun get(url: String, accept: String = "application/json"): String = withContext(Dispatchers.IO) {
        open(url, "GET", accept).use { conn -> read(conn) }
    }

    suspend fun postForm(url: String, form: String, accept: String = "application/json"): String = withContext(Dispatchers.IO) {
        open(url, "POST", accept).use { conn ->
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            read(conn)
        }
    }

    private fun open(url: String, method: String, accept: String): HttpURLConnection {
        require(url.startsWith("https://")) { "Only https is allowed" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
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

        /** Wikimedia and Overpass policies require an identifiable UA with contact; no device identifiers. */
        val DEFAULT_USER_AGENT = "JustTracker/${BuildConfig.VERSION_NAME} (https://alexanderswift89.github.io/JustTracker)"
    }
}
