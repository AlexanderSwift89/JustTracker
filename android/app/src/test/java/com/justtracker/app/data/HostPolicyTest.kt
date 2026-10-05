package com.justtracker.app.data

import com.justtracker.app.data.poi.HostPolicy
import com.justtracker.app.data.poi.HostPolicy.Hop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HostPolicyTest {
    private val summary = "https://en.wikipedia.org/api/rest_v1/page/summary/Kremlin"

    @Test
    fun `allows only overpass and wikipedia over https`() {
        assertTrue(HostPolicy.isAllowed("https://overpass-api.de/api/interpreter"))
        assertTrue(HostPolicy.isAllowed(summary))
        assertTrue(HostPolicy.isAllowed("https://ru.m.wikipedia.org/wiki/%D0%9A"))
        assertTrue(HostPolicy.isAllowed("https://EN.WIKIPEDIA.ORG:443/wiki/X"))
        assertFalse(HostPolicy.isAllowed("https://tile.openstreetmap.org/1/0/0.png"))
        assertFalse(HostPolicy.isAllowed("https://example.com/"))
    }

    @Test
    fun `rejects userinfo, explicit ports, look-alike hosts, ip literals and http`() {
        listOf(
            "http://overpass-api.de/api/interpreter",
            "https://user@en.wikipedia.org/wiki/X",
            "https://en.wikipedia.org:8443/wiki/X",
            "https://en.wikipedia.org.evil.example/wiki/X",
            "https://evil.example/.wikipedia.org/wiki/X",
            "https://wikipedia.org.evil.example/",
            "https://overpass-api.de.evil.example/api/interpreter",
            "https://185.15.59.224/wiki/X",
            "ftp://en.wikipedia.org/x",
            "not a url",
            "",
        ).forEach { assertFalse(it, HostPolicy.isAllowed(it)) }
    }

    @Test
    fun `relative wikipedia redirect resolves against the current url`() {
        assertEquals(
            "https://en.wikipedia.org/api/rest_v1/page/summary/Moscow_Kremlin",
            HostPolicy.resolveRedirect(summary, "Moscow_Kremlin"),
        )
        assertEquals(
            "https://ru.wikipedia.org/api/rest_v1/page/summary/X",
            HostPolicy.resolveRedirect(summary, "https://ru.wikipedia.org/api/rest_v1/page/summary/X"),
        )
    }

    @Test
    fun `redirect to another host, to http or protocol-relative evil host is refused`() {
        assertNull(HostPolicy.resolveRedirect(summary, "https://evil.example/x"))
        assertNull(HostPolicy.resolveRedirect(summary, "http://en.wikipedia.org/api/rest_v1/page/summary/X"))
        assertNull(HostPolicy.resolveRedirect(summary, "//evil.example/x"))
        assertNull(HostPolicy.resolveRedirect(summary, null))
        assertNull(HostPolicy.resolveRedirect(summary, "  "))
        val e = assertThrows(IOException::class.java) {
            HostPolicy.followRedirects(summary) { Hop.Redirect("https://evil.example/x") }
        }
        assertEquals("Redirect not allowed", e.message)
    }

    @Test
    fun `follows up to three redirects`() {
        val visited = ArrayList<String>()
        val result = HostPolicy.followRedirects(summary) { url ->
            visited += url
            if (visited.size <= 3) Hop.Redirect("T${visited.size}") else Hop.Done("body")
        }
        assertEquals("body", result)
        assertEquals(4, visited.size)
        assertTrue(visited.last(), visited.last().endsWith("/page/summary/T3"))
    }

    @Test
    fun `fourth redirect fails`() {
        val e = assertThrows(IOException::class.java) {
            HostPolicy.followRedirects(summary) { Hop.Redirect("Again") }
        }
        assertEquals("Too many redirects", e.message)
    }
}
