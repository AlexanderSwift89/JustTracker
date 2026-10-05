package com.justtracker.app.data

import com.justtracker.app.data.maps.ImportTooLargeException
import com.justtracker.app.data.maps.copyAtMost
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class CopyAtMostTest {
    @Test
    fun `copies a file that fits the budget byte for byte`() {
        val data = ByteArray(10_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        assertEquals(10_000L, copyAtMost(ByteArrayInputStream(data), out, limit = 10_000, bufferSize = 1024))
        assertArrayEquals(data, out.toByteArray())
    }

    @Test(expected = ImportTooLargeException::class)
    fun `stops at the budget instead of filling the disk`() {
        copyAtMost(ByteArrayInputStream(ByteArray(10_001)), ByteArrayOutputStream(), limit = 10_000, bufferSize = 1024)
    }

    @Test
    fun `an endless stream is cut off after the budget`() {
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = len
        }
        val out = ByteArrayOutputStream()
        try {
            copyAtMost(endless, out, limit = 1_000_000, bufferSize = 4096)
            error("must not return")
        } catch (_: ImportTooLargeException) {
            assertEquals(true, out.size() <= 1_000_000)
        }
    }
}
