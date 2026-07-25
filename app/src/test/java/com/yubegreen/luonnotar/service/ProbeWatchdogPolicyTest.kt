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
}
