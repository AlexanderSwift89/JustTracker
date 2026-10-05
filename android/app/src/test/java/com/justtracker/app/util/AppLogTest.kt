package com.justtracker.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.FileNotFoundException

class AppLogTest {
    @Test
    fun `release line names the exception class without its message`() {
        val e = FileNotFoundException("/data/user/0/com.justtracker.app/cache/exports/Home_to_work_7.gpx (No space left)")
        val line = AppLog.releaseLine("GPX export failed", e)
        assertEquals("GPX export failed [java.io.FileNotFoundException]", line)
        assertFalse(line, line.contains("Home_to_work"))
        assertEquals("Max points reached", AppLog.releaseLine("Max points reached", null))
    }
}
