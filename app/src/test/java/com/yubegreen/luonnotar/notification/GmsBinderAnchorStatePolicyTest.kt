package com.yubegreen.luonnotar.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsBinderAnchorStatePolicyTest {
    @Test
    fun `anchor requires both enabled and active guardian`() {
        assertFalse(GmsBinderAnchorStatePolicy.shouldConnect(false, true))
        assertFalse(GmsBinderAnchorStatePolicy.shouldConnect(true, false))
        assertTrue(GmsBinderAnchorStatePolicy.shouldConnect(true, true))
    }

    @Test
    fun `connected state is only accepted for the active session`() {
        assertTrue(
            GmsBinderAnchorSessionPolicy.acceptsCallback(
                true, 4L, 4L, sameSession = true, sameClient = true
            )
        )
        assertFalse(
            GmsBinderAnchorSessionPolicy.acceptsCallback(
                true, 5L, 4L, sameSession = true, sameClient = true
            )
        )
        assertFalse(
            GmsBinderAnchorSessionPolicy.acceptsCallback(
                true, 5L, 5L, sameSession = false, sameClient = true
            )
        )
        assertFalse(
            GmsBinderAnchorSessionPolicy.acceptsCallback(
                true, 5L, 5L, sameSession = true, sameClient = false
            )
        )
        assertFalse(
            GmsBinderAnchorSessionPolicy.acceptsCallback(
                false, 5L, 5L, sameSession = true, sameClient = true
            )
        )
    }

    @Test
    fun `pending reconnect does not consume another attempt`() {
        assertTrue(
            GmsBinderAnchorSessionPolicy.nextReconnectAttempt(2, false) == 3
        )
        assertTrue(
            GmsBinderAnchorSessionPolicy.nextReconnectAttempt(2, true) == 2
        )
    }

    @Test
    fun `connected reset clears attempt failure and suspension`() {
        val reset = GmsBinderAnchorSessionPolicy.connectedReset()
        assertTrue(reset.reconnectAttempt == 0)
        assertTrue(reset.failureCode == 0)
        assertTrue(reset.suspensionCause == 0)
    }
}
