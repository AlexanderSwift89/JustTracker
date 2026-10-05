package com.justtracker.app.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.justtracker.app.data.repo.RemovedFeatureKeysMigration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovedFeatureKeysMigrationTest {
    private val poiEnabled = booleanPreferencesKey("poi_enabled")
    private val poiAutoSpeak = booleanPreferencesKey("poi_auto_speak")
    private val units = stringPreferencesKey("units")
    private val accuracy = intPreferencesKey("max_accuracy_m")

    @Test
    fun `settings of 1_1_1 lose the Places nearby keys and keep everything else`() = runTest {
        val old = mutablePreferencesOf(poiEnabled to true, poiAutoSpeak to true, units to "IMPERIAL", accuracy to 30)

        assertTrue(RemovedFeatureKeysMigration.shouldMigrate(old))
        val migrated = RemovedFeatureKeysMigration.migrate(old)

        assertFalse(poiEnabled in migrated)
        assertFalse(poiAutoSpeak in migrated)
        assertEquals("IMPERIAL", migrated[units])
        assertEquals(30, migrated[accuracy])
    }

    @Test
    fun `nothing to do once the keys are gone`() = runTest {
        assertFalse(RemovedFeatureKeysMigration.shouldMigrate(mutablePreferencesOf(units to "METRIC")))
    }
}
