package com.rangandale.appthrottler.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "throttle_profiles")
data class ThrottleProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val downloadSpeedKbps: Int,
    val uploadSpeedKbps: Int,
    val latencyMs: Int,
    val jitterMs: Int,
    val packetLossPercent: Float,
    val selectedAppPackagesJson: String,
    val isPreset: Boolean,
    val updatedAt: Long,
)