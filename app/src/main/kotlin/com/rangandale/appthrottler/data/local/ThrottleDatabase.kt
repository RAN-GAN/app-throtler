package com.rangandale.appthrottler.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ThrottleProfileEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class ThrottleDatabase : RoomDatabase() {
    abstract fun throttleProfileDao(): ThrottleProfileDao
}