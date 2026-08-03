package com.yubegreen.luonnotar.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeWatchdogPolicyTest {
    @Test
    fun logicalTimeoutFirstRebuildsExecutor() {
        assertEquals(
            ProbeWatchdogAction.REBUILD_EXECUTOR,
            ProbeWatchdogPolicy.action(
                logicalInFlight = true,
                actualInFlight = true,
                ageMs = 15_000L,
                softTimeoutMs = 15_000L,
                hardTimeoutMs = 45_000L
            )
        )
    }

    @Test
    fun oldActualPermitGetsHardProcessRestartLease() {
        assertEquals(
            ProbeWatchdogAction.RESTART_KEEPER_PROCESS,
            ProbeWatchdogPolicy.action(
                logicalInFlight = false,
                actualInFlight = true,
                ageMs = 45_000L,
                softTimeoutMs = 15_000L,
                hardTimeoutMs = 45_000L
            )
        )
    }

    @Test
    fun healthyOrDrainingWithinLeaseDoesNothing() {
        assertEquals(
            ProbeWatchdogAction.NONE,
            ProbeWatchdogPolicy.action(
                logicalInFlight = false,
                actualInFlight = true,
                ageMs = 44_999L,
                softTimeoutMs = 15_000L,
                hardTimeoutMs = 45_000L
            )
        )
    }

    @Test
    fun hardRestartRevalidationRejectsLateOwnerAndVpnChanges() {
        val expectedOwner = ProbeOwnerToken(1L, 3L)
        val expected = ActualProbePermitSnapshot(
            expectedOwner,
            acquiredElapsed = 1_000L,
            networkHandle = 700L,
            stage = "HTTPS"
        )
        assertEquals(
            true,
            ProbeHardRestartPolicy.leaseStillEligible(
                expected,
                expected,
                nowElapsed = 46_000L,
                hardTimeoutMs = 45_000L,
                expectedVpnHandle = 700L,
                currentVpnHandle = 700L
            )
        )
        assertEquals(
            false,
            ProbeHardRestartPolicy.leaseStillEligible(
                expected,
                ActualProbePermitSnapshot(
                    ProbeOwnerToken(2L, 4L),
                    2_000L,
                    700L,
                    "HTTPS"
                ),
                nowElapsed = 50_000L,
                hardTimeoutMs = 45_000L,
                expectedVpnHandle = 700L,
                currentVpnHandle = 700L
            )
        )
        assertEquals(
            false,
            ProbeHardRestartPolicy.leaseStillEligible(
                expected.copy(stage = "MTALK"),
                expected.copy(stage = "MTALK"),
                nowElapsed = 50_000L,
                hardTimeoutMs = 45_000L,
                expectedVpnHandle = 700L,
                currentVpnHandle = 700L
            )
        )
        assertEquals(
            false,
            ProbeHardRestartPolicy.leaseStillEligible(
                expected,
                expected,
                nowElapsed = 50_000L,
                hardTimeoutMs = 45_000L,
                expectedVpnHandle = 700L,
                currentVpnHandle = 701L
            )
        )
    }
}
