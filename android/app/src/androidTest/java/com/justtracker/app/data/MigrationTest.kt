package com.justtracker.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.justtracker.app.data.db.JustTrackerDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Schema 1 (JustTracker 1.0.0) -> 2 (1.0.2): the automatic migration keeps every row and adds a nullable column. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), JustTrackerDatabase::class.java)

    @Test
    fun version1To2KeepsTracksAndPoints() {
        helper.createDatabase(DB, 1).apply {
            execSQL(
                "INSERT INTO tracks (id, name, status, activityType, activityManual, startedAt, finishedAt, distanceM, movingTimeMs, " +
                    "totalTimeMs, pausedTimeMs, avgSpeedMps, maxSpeedMps, elevationGainM, elevationLossM, pointCount) " +
                    "VALUES (1, 'Walk', 'FINISHED', 'WALK', 0, 0, 60000, 120.0, 60000, 60000, 0, 2.0, 2.5, 0.0, 0.0, 2)",
            )
            execSQL("INSERT INTO track_points (id, trackId, segment, timestamp, lat, lon, altitudeM, accuracyM, speedMps, bearingDeg) VALUES (1, 1, 0, 0, 55.0, 37.0, 150.0, 5.0, 2.0, NULL)")
            execSQL("INSERT INTO track_points (id, trackId, segment, timestamp, lat, lon, altitudeM, accuracyM, speedMps, bearingDeg) VALUES (2, 1, 0, 60000, 55.001, 37.0, 151.0, 5.0, 2.0, NULL)")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 2, true)
        db.query("SELECT COUNT(*) FROM track_points WHERE verticalAccuracyM IS NULL").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }
        db.query("SELECT name, pointCount FROM tracks WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Walk", c.getString(0))
            assertEquals(2, c.getInt(1))
        }
        db.close()
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
