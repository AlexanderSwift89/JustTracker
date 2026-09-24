package com.justtracker.app.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Schema history (exported to `schemas/`): 1 — JustTracker 1.0.0; 2 — 1.0.2, `track_points.verticalAccuracyM`
 * (nullable, added by an automatic migration; old points keep null).
 */
@Database(
    entities = [TrackEntity::class, TrackPointEntity::class, OfflineRegionEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class JustTrackerDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun offlineRegionDao(): OfflineRegionDao

    companion object {
        const val NAME = "justtracker.db"

        fun build(context: Context): JustTrackerDatabase =
            Room.databaseBuilder(context.applicationContext, JustTrackerDatabase::class.java, NAME)
                .build()
    }
}
