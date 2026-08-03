package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAdbTcpFallbackPolicyTest {
    @Test
    fun requiresPairedIdentityLivePropertyAndSocket() {
        assertFalse(
            LocalAdbTcpFallbackPolicy.decide(
                paired = false,
                serviceTcpPort = "5555",
                persistedTcpPort = "5555",
                socketReachable = true
            ).allowed
        )
        assertFalse(
            LocalAdbTcpFallbackPolicy.decide(
                paired = true,
                serviceTcpPort = "",
                persistedTcpPort = "5555",
                socketReachable = true
            ).allowed
        )
        assertFalse(
            LocalAdbTcpFallbackPolicy.decide(
                paired = true,
                serviceTcpPort = "5555",
                persistedTcpPort = "5555",
                socketReachable = false
            ).allowed
        )
        assertTrue(
            LocalAdbTcpFallbackPolicy.decide(
                paired = true,
                serviceTcpPort = "5555",
                persistedTcpPort = "",
                socketReachable = true
            ).allowed
        )
    }
}
