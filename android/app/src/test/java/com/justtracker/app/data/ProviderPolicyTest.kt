package com.justtracker.app.data

import com.justtracker.app.data.location.ProviderPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderPolicyTest {
    private val all = setOf("fused", "gps", "network", "passive")

    @Test
    fun `prefers platform fused provider on API 31+`() {
        assertEquals("fused", ProviderPolicy.pick(31, all))
        assertEquals("fused", ProviderPolicy.pick(36, all))
    }

    @Test
    fun `ignores fused below API 31 and falls back to gps`() {
        assertEquals("gps", ProviderPolicy.pick(30, all))
        assertEquals("gps", ProviderPolicy.pick(26, all))
    }

    @Test
    fun `falls back to gps then network when fused is absent`() {
        assertEquals("gps", ProviderPolicy.pick(33, setOf("gps", "network")))
        assertEquals("network", ProviderPolicy.pick(33, setOf("network", "passive")))
    }

    @Test
    fun `returns null when nothing usable exists`() {
        assertNull(ProviderPolicy.pick(33, setOf("passive")))
        assertNull(ProviderPolicy.pick(26, emptySet()))
    }

    @Test
    fun `last known order lists fused only on API 31+`() {
        assertEquals(listOf("fused", "gps", "network", "passive"), ProviderPolicy.lastKnownOrder(31))
        assertEquals(listOf("gps", "network", "passive"), ProviderPolicy.lastKnownOrder(30))
    }
}
