package com.rangandale.appthrottler.presentation.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {

    @Test
    fun `speed scale covers the range and ends in unlimited`() {
        assertEquals(SpeedScale.MIN_KBPS, SpeedScale.toKbps(0f))
        assertEquals(SpeedScale.MAX_KBPS, SpeedScale.toKbps(0.95f))
        assertEquals(SpeedScale.UNLIMITED, SpeedScale.toKbps(1f))
        assertEquals(1f, SpeedScale.toPosition(SpeedScale.UNLIMITED))
    }

    @Test
    fun `speed scale is monotonic and round-trips`() {
        var previous = 0
        for (step in 0..95) {
            val kbps = SpeedScale.toKbps(step / 100f)
            assertTrue("kbps must not decrease at step $step", kbps >= previous)
            assertEquals(kbps, SpeedScale.toKbps(SpeedScale.toPosition(kbps)))
            previous = kbps
        }
    }

    @Test
    fun `preset speeds stay within the slider`() {
        for (kbps in listOf(20, 40, 128, 384, 512, 1_024, 5_120, 10_240)) {
            val position = SpeedScale.toPosition(kbps)
            assertTrue(position in 0f..0.95f)
        }
    }

    @Test
    fun `csv fields are quoted only when needed`() {
        val csv = com.rangandale.appthrottler.presentation.viewmodel.ConnectionsViewModel::csvField
        assertEquals("example.com", csv("example.com"))
        assertEquals("\"Maps, Navigation\"", csv("Maps, Navigation"))
        assertEquals("\"say \"\"hi\"\"\"", csv("say \"hi\""))
    }

    @Test
    fun `scenario steps come from profiles and add up`() {
        val scenario = com.rangandale.appthrottler.domain.model.BuiltInScenarios.elevator
        assertEquals(50, scenario.totalSeconds)
        assertEquals(100f, scenario.steps[1].params.packetLossPercent)
        assertEquals("Offline", scenario.steps[1].label)
    }

    @Test
    fun `formatting`() {
        assertEquals("Unlimited", formatSpeedLimit(0))
        assertEquals("40 kbps", formatSpeedLimit(40))
        assertEquals("1 Mbps", formatSpeedLimit(1_000))
        assertEquals("10.2 Mbps", formatSpeedLimit(10_240))
        assertEquals("40 kbps", formatRate(5_000))
        assertEquals("999 B", formatBytes(999))
        assertEquals("1.5 MB", formatBytes(1_500_000))
        assertEquals("300 ms ± 50 ms", formatLatency(300, 50))
        assertEquals("20 ms", formatLatency(20, 0))
        assertEquals("0.5%", formatPacketLoss(0.5f))
    }
}
