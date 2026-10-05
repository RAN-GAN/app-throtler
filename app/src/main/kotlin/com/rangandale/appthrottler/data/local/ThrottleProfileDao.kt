package com.rangandale.appthrottler.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ThrottleProfileDao {
    @Query("SELECT * FROM throttle_profiles ORDER BY updatedAt DESC")
    fun getAllProfiles(): Flow<List<ThrottleProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ThrottleProfileEntity)

    @Query("DELETE FROM throttle_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Delete
    suspend fun delete(profile: ThrottleProfileEntity)
}