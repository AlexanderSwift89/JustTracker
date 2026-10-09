package com.justtracker.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.justtracker.app.data.db.JustTrackerDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Schema 1 (JustTracker 1.0.0) -> 2 (1.0.2) -> 3 (1.2.0): each automatic migration keeps every row and adds a nullable
 * column the old points keep empty.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), JustTrackerDatabase::class.java)

    @Test
    fun version1To2KeepsTracksAndPoints() {
        createVersion1()
        val db = helper.runMigrationsAndValidate(DB, 2, true)
        assertEquals(2, count(db, "SELECT COUNT(*) FROM track_points WHERE verticalAccuracyM IS NULL"))
        assertTrackKept(db)
        db.close()
    }

    @Test
    fun version2To3KeepsPointsWithoutSpeedAccuracy() {
        helper.createDatabase(DB, 2).apply {
            insertTrack()
            execSQL(
                "INSERT INTO track_points (id, trackId, segment, timestamp, lat, lon, altitudeM, accuracyM, speedMps, bearingDeg, verticalAccuracyM) " +
                    "VALUES (1, 1, 0, 0, 55.0, 37.0, 150.0, 5.0, 2.0, NULL, 3.0)",
            )
            execSQL(
                "INSERT INTO track_points (id, trackId, segment, timestamp, lat, lon, altitudeM, accuracyM, speedMps, bearingDeg, verticalAccuracyM) " +
                    "VALUES (2, 1, 0, 60000, 55.001, 37.0, 151.0, 5.0, 2.0, NULL, NULL)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 3, true)
        assertEquals(2, count(db, "SELECT COUNT(*) FROM track_points WHERE speedAccuracyMps IS NULL"))
        assertEquals(1, count(db, "SELECT COUNT(*) FROM track_points WHERE verticalAccuracyM = 3.0"))
        assertTrackKept(db)
        db.close()
    }

    @Test
    fun version1To3RunsBothMigrations() {
        createVersion1()
        val db = helper.runMigrationsAndValidate(DB, 3, true)
        assertEquals(2, count(db, "SELECT COUNT(*) FROM track_points WHERE verticalAccuracyM IS NULL AND speedAccuracyMps IS NULL"))
        assertTrackKept(db)
        db.close()
    }

    private fun createVersion1() {
        helper.createDatabase(DB, 1).apply {
            insertTrack()
            execSQL("INSERT INTO track_points (id, trackId, segment, timestamp, lat, lon, altitudeM, accuracyM, speedMps, bearingDeg) VALUES (1, 1, 0, 0, 55.0, 37.0, 150.0, 5.0, 2.0, NULL)")
            execSQL("INSERT INTO track_points (id, trackId, segment, timestamp, lat, lon, altitudeM, accuracyM, speedMps, bearingDeg) VALUES (2, 1, 0, 60000, 55.001, 37.0, 151.0, 5.0, 2.0, NULL)")
            close()
        }
    }

    private fun SupportSQLiteDatabase.insertTrack() = execSQL(
        "INSERT INTO tracks (id, name, status, activityType, activityManual, startedAt, finishedAt, distanceM, movingTimeMs, " +
            "totalTimeMs, pausedTimeMs, avgSpeedMps, maxSpeedMps, elevationGainM, elevationLossM, pointCount) " +
            "VALUES (1, 'Walk', 'FINISHED', 'WALK', 0, 0, 60000, 120.0, 60000, 60000, 0, 2.0, 2.5, 0.0, 0.0, 2)",
    )

    private fun count(db: SupportSQLiteDatabase, sql: String): Int = db.query(sql).use { c ->
        assertTrue(c.moveToFirst())
        c.getInt(0)
    }

    private fun assertTrackKept(db: SupportSQLiteDatabase) {
        db.query("SELECT name, pointCount FROM tracks WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Walk", c.getString(0))
            assertEquals(2, c.getInt(1))
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
