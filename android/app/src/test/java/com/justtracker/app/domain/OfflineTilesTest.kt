package com.justtracker.app.domain

import com.justtracker.app.domain.maps.MapFileStamp
import com.justtracker.app.domain.maps.OfflineTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules of the offline tile pipeline (docs/05_architecture.md §12, ADR-17). */
class OfflineTilesTest {
    private val central = MapFileStamp("/maps/ru-central.map", 810_000_000L, 1_700_000_000_000L)
    private val crimea = MapFileStamp("/maps/crimea.map", 36_000_000L, 1_700_000_100_000L)

    @Test
    fun `dense screens get 512 px tiles, others 256`() {
        assertEquals(256, OfflineTiles.tileSizeFor(1.0f))
        assertEquals(256, OfflineTiles.tileSizeFor(1.33f))
        assertEquals(512, OfflineTiles.tileSizeFor(1.5f))
        assertEquals(512, OfflineTiles.tileSizeFor(2.75f))
        assertEquals(512, OfflineTiles.tileSizeFor(3.5f))
    }

    @Test
    fun `render scale follows the tile size`() {
        assertEquals(1f, OfflineTiles.scaleFor(256), 0f)
        assertEquals(2f, OfflineTiles.scaleFor(512), 0f)
    }

    @Test
    fun `render threads use half the cores within 2 to 4`() {
        assertEquals(2, OfflineTiles.renderThreads(1))
        assertEquals(2, OfflineTiles.renderThreads(4))
        assertEquals(3, OfflineTiles.renderThreads(6))
        assertEquals(4, OfflineTiles.renderThreads(8))
        assertEquals(4, OfflineTiles.renderThreads(16))
    }

    @Test
    fun `fingerprint is stable and independent of file order`() {
        val a = OfflineTiles.fingerprint(listOf(central, crimea), "ru", 512)
        val b = OfflineTiles.fingerprint(listOf(crimea, central), "ru", 512)
        assertEquals(a, b)
        assertEquals(16, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `fingerprint changes with file contents, language and tile size`() {
        val base = OfflineTiles.fingerprint(listOf(central), "ru", 512)
        assertNotEquals(base, OfflineTiles.fingerprint(listOf(central.copy(modifiedAt = central.modifiedAt + 1)), "ru", 512))
        assertNotEquals(base, OfflineTiles.fingerprint(listOf(central.copy(sizeBytes = central.sizeBytes + 1)), "ru", 512))
        assertNotEquals(base, OfflineTiles.fingerprint(listOf(central), "en", 512))
        assertNotEquals(base, OfflineTiles.fingerprint(listOf(central), null, 512))
        assertNotEquals(base, OfflineTiles.fingerprint(listOf(central), "ru", 256))
        assertNotEquals(base, OfflineTiles.fingerprint(listOf(central, crimea), "ru", 512))
    }

    @Test
    fun `cache namespace is recognisable and distinct from the online source`() {
        val name = OfflineTiles.sourceName(OfflineTiles.fingerprint(listOf(central), "ru", 512))
        assertTrue(name.startsWith("JustTrackerOffline-"))
        assertTrue(OfflineTiles.isOfflineSource(name))
        assertFalse(OfflineTiles.isOfflineSource("Mapnik"))
        assertFalse(OfflineTiles.isOfflineSource("JustTrackerOffline"))
    }
}
