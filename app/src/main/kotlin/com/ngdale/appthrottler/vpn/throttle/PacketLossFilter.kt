package com.ngdale.appthrottler.vpn.throttle

import kotlin.random.Random

object PacketLossFilter {
    fun shouldDrop(packetLossPercent: Float): Boolean {
        if (packetLossPercent <= 0f) return false
        if (packetLossPercent >= 100f) return true
        return Random.nextFloat() * 100f < packetLossPercent
    }
}