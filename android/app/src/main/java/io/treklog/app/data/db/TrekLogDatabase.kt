package io.treklog.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, TrackPointEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TrekLogDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        const val NAME = "treklog.db"

        fun build(context: Context): TrekLogDatabase =
            Room.databaseBuilder(context.applicationContext, TrekLogDatabase::class.java, NAME)
                .build()
    }
}
