package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlPlaneRecoveryPolicyTest {
    @Test
    fun phasesCoverDisabledHealthyGraceDueAndBackoff() {
        val grace = 15_000L
        val cooldown = 60_000L

        assertEquals(
            ControlPlaneRecoveryPolicy.Phase.DISABLED,
            ControlPlaneRecoveryPolicy.phase(20_000L, false, false, 1_000L, 0L, grace, cooldown)
        )
        assertEquals(
            ControlPlaneRecoveryPolicy.Phase.HEALTHY,
            ControlPlaneRecoveryPolicy.phase(20_000L, true, true, 0L, 0L, grace, cooldown)
        )
        assertEquals(
            ControlPlaneRecoveryPolicy.Phase.MISSING_GRACE,
            ControlPlaneRecoveryPolicy.phase(14_999L, true, false, 1L, 0L, grace, cooldown)
        )
        assertEquals(
            ControlPlaneRecoveryPolicy.Phase.RECOVERY_DUE,
            ControlPlaneRecoveryPolicy.phase(15_001L, true, false, 1L, 0L, grace, cooldown)
        )
        assertEquals(
            ControlPlaneRecoveryPolicy.Phase.BACKOFF,
            ControlPlaneRecoveryPolicy.phase(70_000L, true, false, 1L, 20_000L, grace, cooldown)
        )
        assertEquals(
            ControlPlaneRecoveryPolicy.Phase.RECOVERY_DUE,
            ControlPlaneRecoveryPolicy.phase(80_000L, true, false, 1L, 20_000L, grace, cooldown)
        )
    }

    @Test
    fun nextEligibleTimestampMatchesGraceAndCooldown() {
        assertEquals(
            16_000L,
            ControlPlaneRecoveryPolicy.nextRecoveryEligibleElapsed(
                nowElapsed = 5_000L,
                enabled = true,
                healthy = false,
                missingSinceElapsed = 1_000L,
                lastRecoveryElapsed = 0L,
                graceMs = 15_000L,
                cooldownMs = 60_000L
            )
        )
        assertEquals(
            80_000L,
            ControlPlaneRecoveryPolicy.nextRecoveryEligibleElapsed(
                nowElapsed = 70_000L,
                enabled = true,
                healthy = false,
                missingSinceElapsed = 1_000L,
                lastRecoveryElapsed = 20_000L,
                graceMs = 15_000L,
                cooldownMs = 60_000L
            )
        )
        assertEquals(
            -1L,
            ControlPlaneRecoveryPolicy.nextRecoveryEligibleElapsed(
                nowElapsed = 70_000L,
                enabled = true,
                healthy = true,
                missingSinceElapsed = 0L,
                lastRecoveryElapsed = 0L,
                graceMs = 15_000L,
                cooldownMs = 60_000L
            )
        )
    }

    @Test
    fun probeCadenceIsSuspendSafeAndImmediateAfterClockReset() {
        assertTrue(ControlPlaneRecoveryPolicy.shouldProbe(100_000L, 0L, 15_000L))
        assertFalse(ControlPlaneRecoveryPolicy.shouldProbe(114_999L, 100_000L, 15_000L))
        assertTrue(ControlPlaneRecoveryPolicy.shouldProbe(115_000L, 100_000L, 15_000L))
        assertTrue(ControlPlaneRecoveryPolicy.shouldProbe(10_000L, 100_000L, 15_000L))
    }

    @Test
    fun listenerParserHandlesIpv4Ipv6AndLoopback() {
        val output = """
            LISTEN 0 4 0.0.0.0:5555 0.0.0.0:*
            LISTEN 0 4 [::]:8022 [::]:*
            LISTEN 0 4 [::ffff:127.0.0.1]:8025 *:*
        """.trimIndent()

        assertTrue(ControlPlaneRecoveryPolicy.listeningOnPort(output, 5555))
        assertTrue(ControlPlaneRecoveryPolicy.listeningOnPort(output, 8022))
        assertTrue(ControlPlaneRecoveryPolicy.listeningOnPort(output, 8025))
        assertFalse(ControlPlaneRecoveryPolicy.listeningOnPort(output, 5037))
    }
}
