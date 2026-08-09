package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxSshdHealthPolicyTest {
    @Test
    fun requiresBothProcessAndConfiguredListenerAtCallSite() {
        assertTrue(TermuxSshdHealthPolicy.processRunning("1234 5678"))
        assertFalse(TermuxSshdHealthPolicy.processRunning(""))
        assertTrue(
            TermuxSshdHealthPolicy.listeningOnPort(
                "LISTEN 0 4 0.0.0.0:8022 0.0.0.0:*",
                8022
            )
        )
        assertFalse(
            TermuxSshdHealthPolicy.listeningOnPort(
                "LISTEN 0 4 0.0.0.0:8025 0.0.0.0:*",
                8022
            )
        )
    }

    @Test
    fun usesSameMainlineRecoveryCadenceAsAdbChannel() {
        assertFalse(TermuxSshdHealthPolicy.shouldRecover(114_999L, true, 100_000L, 0L))
        assertTrue(TermuxSshdHealthPolicy.shouldRecover(115_000L, true, 100_000L, 0L))
        assertFalse(TermuxSshdHealthPolicy.shouldRecover(159_999L, true, 100_000L, 100_000L))
        assertTrue(TermuxSshdHealthPolicy.shouldRecover(160_000L, true, 100_000L, 100_000L))
    }

    @Test
    fun exposesBackoffPhaseAndDeadline() {
        assertEquals(
            ControlPlaneRecoveryPolicy.Phase.BACKOFF,
            TermuxSshdHealthPolicy.phase(150_000L, true, false, 100_000L, 100_000L)
        )
        assertEquals(
            160_000L,
            TermuxSshdHealthPolicy.nextRecoveryEligibleElapsed(
                150_000L, true, false, 100_000L, 100_000L
            )
        )
    }
}
