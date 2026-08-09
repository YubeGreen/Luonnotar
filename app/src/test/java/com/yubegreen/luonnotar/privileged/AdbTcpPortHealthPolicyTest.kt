package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbTcpPortHealthPolicyTest {
    @Test
    fun detectsIpv4AndIpv6AdbListeners() {
        assertTrue(
            AdbTcpPortHealthPolicy.listeningOnPort(
                "LISTEN 0 4 0.0.0.0:5555 0.0.0.0:*"
            )
        )
        assertTrue(
            AdbTcpPortHealthPolicy.listeningOnPort(
                "LISTEN 0 4 [::]:5555 [::]:*"
            )
        )
        assertFalse(
            AdbTcpPortHealthPolicy.listeningOnPort(
                "ESTAB 0 0 100.117.209.84:5555 100.64.0.1:40000"
            )
        )
        assertFalse(
            AdbTcpPortHealthPolicy.listeningOnPort(
                "LISTEN 0 4 127.0.0.1:5037 0.0.0.0:*"
            )
        )
    }

    @Test
    fun recoveryRequiresArmingGraceAndCooldown() {
        assertFalse(
            AdbTcpPortHealthPolicy.shouldRecover(
                nowElapsed = 115_000L,
                armed = false,
                missingSinceElapsed = 100_000L,
                lastRecoveryElapsed = 0L
            )
        )
        assertFalse(
            AdbTcpPortHealthPolicy.shouldRecover(
                nowElapsed = 114_999L,
                armed = true,
                missingSinceElapsed = 100_000L,
                lastRecoveryElapsed = 0L
            )
        )
        assertTrue(
            AdbTcpPortHealthPolicy.shouldRecover(
                nowElapsed = 115_000L,
                armed = true,
                missingSinceElapsed = 100_000L,
                lastRecoveryElapsed = 0L
            )
        )
        assertFalse(
            AdbTcpPortHealthPolicy.shouldRecover(
                nowElapsed = 159_999L,
                armed = true,
                missingSinceElapsed = 100_000L,
                lastRecoveryElapsed = 100_000L
            )
        )
        assertTrue(
            AdbTcpPortHealthPolicy.shouldRecover(
                nowElapsed = 160_000L,
                armed = true,
                missingSinceElapsed = 100_000L,
                lastRecoveryElapsed = 100_000L
            )
        )
    }

    @Test
    fun probesAtFifteenSecondCadence() {
        assertTrue(AdbTcpPortHealthPolicy.shouldProbe(100_000L, 0L))
        assertFalse(AdbTcpPortHealthPolicy.shouldProbe(114_999L, 100_000L))
        assertTrue(AdbTcpPortHealthPolicy.shouldProbe(115_000L, 100_000L))
    }

    @Test
    fun exposesMainlinePhaseAndDeadline() {
        assertEquals(
            ControlPlaneRecoveryPolicy.Phase.MISSING_GRACE,
            AdbTcpPortHealthPolicy.phase(110_000L, true, false, 100_000L, 0L)
        )
        assertEquals(
            115_000L,
            AdbTcpPortHealthPolicy.nextRecoveryEligibleElapsed(
                110_000L, true, false, 100_000L, 0L
            )
        )
    }
}
