package com.justtracker.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, TrackPointEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class JustTrackerDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        const val NAME = "justtracker.db"

        fun build(context: Context): JustTrackerDatabase =
            Room.databaseBuilder(context.applicationContext, JustTrackerDatabase::class.java, NAME)
                .build()
    }
}
