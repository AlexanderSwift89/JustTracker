package com.justtracker.app.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rows of the landscape stats pane: the start date/time tile takes a whole row — as a half-width tile it
 * showed "23 сент." instead of "23 сент. 2026 г., 20:36".
 */
class StatGridRowsTest {
    private fun rows(vararg items: String) = gridRows(items.toList()) { it.startsWith("wide") }

    @Test
    fun `items pair up in order`() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d"), listOf("e")), rows("a", "b", "c", "d", "e"))
    }

    @Test
    fun `a wide item after an even count gets its own row`() {
        assertEquals(listOf(listOf("a", "b"), listOf("wide")), rows("a", "b", "wide"))
    }

    @Test
    fun `a wide item after an odd count leaves the item before it alone`() {
        // Same placement as GridItemSpan(maxLineSpan) in the portrait LazyVerticalGrid.
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("wide")), rows("a", "b", "c", "wide"))
    }

    @Test
    fun `pairing continues after a wide item`() {
        assertEquals(listOf(listOf("wide1"), listOf("a", "b"), listOf("wide2")), rows("wide1", "a", "b", "wide2"))
    }

    @Test
    fun `no items no rows`() {
        assertEquals(emptyList<List<String>>(), rows())
    }
}
