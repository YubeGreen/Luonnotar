package com.yubegreen.luonnotar.privileged

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
                nowElapsed = 200_000L,
                armed = false,
                missingSinceElapsed = 100_000L,
                lastRecoveryElapsed = 0L
            )
        )
        assertFalse(
            AdbTcpPortHealthPolicy.shouldRecover(
                nowElapsed = 150_000L,
                armed = true,
                missingSinceElapsed = 100_000L,
                lastRecoveryElapsed = 0L
            )
        )
        assertTrue(
            AdbTcpPortHealthPolicy.shouldRecover(
                nowElapsed = 200_000L,
                armed = true,
                missingSinceElapsed = 100_000L,
                lastRecoveryElapsed = 0L
            )
        )
        assertFalse(
            AdbTcpPortHealthPolicy.shouldRecover(
                nowElapsed = 300_000L,
                armed = true,
                missingSinceElapsed = 100_000L,
                lastRecoveryElapsed = 250_000L
            )
        )
    }
    @Test
    fun probesImmediatelyThenAtOneMinuteIntervals() {
        assertTrue(
            AdbTcpPortHealthPolicy.shouldProbe(
                nowElapsed = 100_000L,
                lastProbeElapsed = 0L
            )
        )
        assertFalse(
            AdbTcpPortHealthPolicy.shouldProbe(
                nowElapsed = 150_000L,
                lastProbeElapsed = 100_000L
            )
        )
        assertTrue(
            AdbTcpPortHealthPolicy.shouldProbe(
                nowElapsed = 160_000L,
                lastProbeElapsed = 100_000L
            )
        )
    }

}
