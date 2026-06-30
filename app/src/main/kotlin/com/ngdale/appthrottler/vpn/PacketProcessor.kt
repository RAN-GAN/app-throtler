package com.ngdale.appthrottler.vpn

import com.ngdale.appthrottler.domain.model.ThrottleConfig
import java.util.concurrent.atomic.AtomicBoolean

class PacketProcessor(
    private val config: ThrottleConfig,
) {

    private val running = AtomicBoolean(false)

    fun start() {
        running.set(true)
    }

    fun stop() {
        running.set(false)
    }

    fun isRunning(): Boolean = running.get()

    fun currentConfig(): ThrottleConfig = config
}