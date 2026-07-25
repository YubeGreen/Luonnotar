package com.yubegreen.luonnotar.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepaliveCadencePolicyTest {
    @Test
    fun aggressiveModeUsesThirtySecondsWhileScreenIsOff() {
        assertEquals(
            30_000L,
            KeepaliveCadencePolicy.intervalMs(
                aggressiveMode = true,
                screenInteractive = false
            )
        )
    }

    @Test
    fun screenOnReturnsToNormalFiveMinuteInterval() {
        assertEquals(
            5 * 60_000L,
            KeepaliveCadencePolicy.intervalMs(
                aggressiveMode = true,
                screenInteractive = true
            )
        )
    }

    @Test
    fun missingVpnNeverAllowsProbe() {
        assertFalse(
            KeepaliveCadencePolicy.mayProbe(
                vpnPresent = false,
                paused = false
            )
        )
        assertTrue(
            KeepaliveCadencePolicy.mayProbe(
                vpnPresent = true,
                paused = false
            )
        )
    }
}
