package com.yubegreen.luonnotar.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationListenerRecoveryPolicyTest {
    @Test
    fun requestsRebindWhenHeartbeatIsStale() {
        assertTrue(
            NotificationListenerRecoveryPolicy.shouldRequestRebind(
                nowElapsed = 300_000L,
                connected = true,
                heartbeatElapsed = 100_000L,
                lastRequestElapsed = 0L
            )
        )
    }

    @Test
    fun respectsRebindCooldown() {
        assertFalse(
            NotificationListenerRecoveryPolicy.shouldRequestRebind(
                nowElapsed = 300_000L,
                connected = false,
                heartbeatElapsed = 0L,
                lastRequestElapsed = 270_000L
            )
        )
    }

    @Test
    fun healthyHeartbeatDoesNotRebind() {
        assertFalse(
            NotificationListenerRecoveryPolicy.shouldRequestRebind(
                nowElapsed = 300_000L,
                connected = true,
                heartbeatElapsed = 250_000L,
                lastRequestElapsed = 0L
            )
        )
    }
}
