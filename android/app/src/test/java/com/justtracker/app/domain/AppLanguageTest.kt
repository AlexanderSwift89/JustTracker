package com.justtracker.app.domain

import com.justtracker.app.domain.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {
    @Test
    fun `matches bare tags and regional variants`() {
        assertEquals(AppLanguage.RU, AppLanguage.fromTag("ru"))
        assertEquals(AppLanguage.RU, AppLanguage.fromTag("ru-RU"))
        assertEquals(AppLanguage.RU, AppLanguage.fromTag("ru_RU"))
        assertEquals(AppLanguage.EN, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.EN, AppLanguage.fromTag("EN"))
    }

    @Test
    fun `unsupported or missing tags give null`() {
        assertNull(AppLanguage.fromTag("de"))
        assertNull(AppLanguage.fromTag(""))
        assertNull(AppLanguage.fromTag(null))
    }

    @Test
    fun `stored tag round-trips`() {
        AppLanguage.entries.forEach { assertEquals(it, AppLanguage.fromTag(it.tag)) }
    }
}
