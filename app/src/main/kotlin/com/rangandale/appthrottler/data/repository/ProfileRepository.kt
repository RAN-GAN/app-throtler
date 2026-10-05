package com.rangandale.appthrottler.data.repository

import com.rangandale.appthrottler.data.local.Converters
import com.rangandale.appthrottler.data.local.ThrottleProfileDao
import com.rangandale.appthrottler.data.local.ThrottleProfileEntity
import com.rangandale.appthrottler.domain.model.ThrottleProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProfileRepository(
    private val dao: ThrottleProfileDao,
) {
    private val converters = Converters()

    fun getAllProfiles(): Flow<List<ThrottleProfile>> = dao.getAllProfiles().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun save(profile: ThrottleProfile) {
        dao.insert(profile.toEntity())
    }

    suspend fun delete(profileId: Long) {
        dao.deleteById(profileId)
    }

    private fun ThrottleProfileEntity.toDomain(): ThrottleProfile = ThrottleProfile(
        id = id,
        name = name,
        downloadSpeedKbps = downloadSpeedKbps,
        uploadSpeedKbps = uploadSpeedKbps,
        latencyMs = latencyMs,
        jitterMs = jitterMs,
        packetLossPercent = packetLossPercent,
        selectedAppPackages = converters.fromJson(selectedAppPackagesJson),
        isPreset = isPreset,
    )

    private fun ThrottleProfile.toEntity(): ThrottleProfileEntity = ThrottleProfileEntity(
        id = id,
        name = name,
        downloadSpeedKbps = downloadSpeedKbps,
        uploadSpeedKbps = uploadSpeedKbps,
        latencyMs = latencyMs,
        jitterMs = jitterMs,
        packetLossPercent = packetLossPercent,
        selectedAppPackagesJson = converters.toJson(selectedAppPackages),
        isPreset = isPreset,
        updatedAt = System.currentTimeMillis(),
    )
}