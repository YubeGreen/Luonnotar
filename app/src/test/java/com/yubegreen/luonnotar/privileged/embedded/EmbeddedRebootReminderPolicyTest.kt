package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedRebootReminderPolicyTest {
    @Test
    fun `pending reminder posts while feature enabled and engine disconnected`() {
        assertEquals(
            EmbeddedRebootReminderPolicy.Action.POST,
            EmbeddedRebootReminderPolicy.decide(
                featureEnabled = true,
                liveConnected = false,
                pending = true
            )
        )
    }

    @Test
    fun `verified live engine cancels any stale reminder`() {
        assertEquals(
            EmbeddedRebootReminderPolicy.Action.CANCEL,
            EmbeddedRebootReminderPolicy.decide(
                featureEnabled = true,
                liveConnected = true,
                pending = true
            )
        )
    }

    @Test
    fun `disabled feature cancels reminder`() {
        assertEquals(
            EmbeddedRebootReminderPolicy.Action.CANCEL,
            EmbeddedRebootReminderPolicy.decide(
                featureEnabled = false,
                liveConnected = false,
                pending = true
            )
        )
    }

    @Test
    fun `no pending evidence does not invent a reboot reminder`() {
        assertEquals(
            EmbeddedRebootReminderPolicy.Action.NONE,
            EmbeddedRebootReminderPolicy.decide(
                featureEnabled = true,
                liveConnected = false,
                pending = false
            )
        )
    }
}
