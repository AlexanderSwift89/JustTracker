package com.justtracker.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.justtracker.app.data.repo.SettingsRepository
import com.justtracker.app.domain.model.LineMetric
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {
    @get:Rule
    val dir = TemporaryFolder()

    @Test
    fun `the detail line metric is speed until switched, then kept`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir.root, "settings.preferences_pb") }
        val settings = SettingsRepository(store)
        assertEquals(LineMetric.SPEED, settings.current().detailLineMetric)
        settings.setDetailLineMetric(LineMetric.ACCELERATION)
        assertEquals(LineMetric.ACCELERATION, settings.current().detailLineMetric)
        // A value this version does not know (a later one wrote it) reads as the default.
        store.edit { it[stringPreferencesKey("detail_line_metric")] = "CADENCE" }
        assertEquals(LineMetric.SPEED, settings.current().detailLineMetric)
    }
}
