package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedGuardianBindingPolicyTest {
    @Test
    fun freshBindingCanBeReused() {
        assertTrue(
            PrivilegedGuardianBindingPolicy.canReuseInFlightBind(
                bindingFlag = true,
                remoteConnected = false,
                connectionState = "binding",
                stateAgeMs = 1_000L
            )
        )
    }

    @Test
    fun disabledStateWithBindingFlagIsReset() {
        assertTrue(
            PrivilegedGuardianBindingPolicy.shouldResetStaleBind(
                bindingFlag = true,
                remoteConnected = false,
                connectionState = "disabled",
                stateAgeMs = 100L
            )
        )
    }

    @Test
    fun timedOutBindingIsReset() {
        assertTrue(
            PrivilegedGuardianBindingPolicy.shouldResetStaleBind(
                bindingFlag = true,
                remoteConnected = false,
                connectionState = "binding",
                stateAgeMs = PrivilegedGuardianBindingPolicy.BIND_TIMEOUT_MS
            )
        )
    }

    @Test
    fun connectedRemoteNeverReusesBindingFlag() {
        assertFalse(
            PrivilegedGuardianBindingPolicy.canReuseInFlightBind(
                bindingFlag = true,
                remoteConnected = true,
                connectionState = "binding",
                stateAgeMs = 100L
            )
        )
    }
}
