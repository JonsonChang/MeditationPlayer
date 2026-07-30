package com.wji.meditationplayer.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [TrackEntity::class], version = 1, exportSchema = true)
@TypeConverters(GapListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
}
