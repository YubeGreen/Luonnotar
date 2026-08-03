package com.yubegreen.luonnotar.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentHeartbeatBackoffPolicyTest {
    @Test
    fun `retry delay grows exponentially and caps at five minutes`() {
        assertEquals(5_000L, PersistentHeartbeatBackoffPolicy.retryDelayMs(1))
        assertEquals(10_000L, PersistentHeartbeatBackoffPolicy.retryDelayMs(2))
        assertEquals(20_000L, PersistentHeartbeatBackoffPolicy.retryDelayMs(3))
        assertEquals(40_000L, PersistentHeartbeatBackoffPolicy.retryDelayMs(4))
        assertEquals(80_000L, PersistentHeartbeatBackoffPolicy.retryDelayMs(5))
        assertEquals(160_000L, PersistentHeartbeatBackoffPolicy.retryDelayMs(6))
        assertEquals(300_000L, PersistentHeartbeatBackoffPolicy.retryDelayMs(7))
        assertEquals(300_000L, PersistentHeartbeatBackoffPolicy.retryDelayMs(99))
    }

    @Test
    fun `storm detector requires eight recent attempts`() {
        val now = 1_000_000L
        val recent = (0 until 7).map { now - it * 1_000L }
        assertFalse(
            PersistentHeartbeatBackoffPolicy.isReconnectStorm(recent, now)
        )
        assertTrue(
            PersistentHeartbeatBackoffPolicy.isReconnectStorm(
                recent + (now - 8_000L),
                now
            )
        )
    }

    @Test
    fun `storm detector ignores attempts outside the window`() {
        val now = 2_000_000L
        val stale = List(20) {
            now -
                PersistentHeartbeatBackoffPolicy
                    .RECONNECT_STORM_WINDOW_MS -
                1L -
                it
        }
        assertFalse(
            PersistentHeartbeatBackoffPolicy.isReconnectStorm(stale, now)
        )
    }
}
