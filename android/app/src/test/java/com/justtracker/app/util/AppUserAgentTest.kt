package com.justtracker.app.util

import com.justtracker.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUserAgentTest {
    @Test
    fun `user agent names app and version without device details`() {
        assertEquals("JustTracker/1.1.1 (https://alexanderswift89.github.io/JustTracker)", AppUserAgent.format("1.1.1"))
        val ua = AppUserAgent.value
        assertTrue(ua, ua.startsWith("JustTracker/${BuildConfig.VERSION_NAME} (https://"))
        // What the system DownloadManager / Dalvik agents would add: OS release, device model, build id.
        listOf("Android", "Linux", "Build/", "Dalvik", ";").forEach { assertFalse(ua, ua.contains(it)) }
    }
}
