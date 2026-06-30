package com.ngdale.appthrottler.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ThrottleProfileEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ThrottleDatabase : RoomDatabase() {
    abstract fun throttleProfileDao(): ThrottleProfileDao
}