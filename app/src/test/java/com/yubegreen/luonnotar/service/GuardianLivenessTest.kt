package com.yubegreen.luonnotar.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianLivenessTest {
    @Test
    fun `missing heartbeat is stale`() {
        assertTrue(GuardianLiveness.isStale(nowElapsed = 100_000, heartbeatElapsed = 0))
    }

    @Test
    fun `heartbeat from a previous boot is stale`() {
        assertTrue(GuardianLiveness.isStale(nowElapsed = 20_000, heartbeatElapsed = 900_000))
    }

    @Test
    fun `fresh heartbeat is alive`() {
        assertFalse(GuardianLiveness.isStale(nowElapsed = 100_000, heartbeatElapsed = 95_000))
    }

    @Test
    fun `heartbeat older than threshold is stale`() {
        assertTrue(
            GuardianLiveness.isStale(
                nowElapsed = 200_001,
                heartbeatElapsed = 100_000,
                thresholdMs = 100_000
            )
        )
    }

    @Test
    fun `fresh heartbeat with replaced keeper pid requires recovery`() {
        assertTrue(
            GuardianLiveness.shouldRecover(
                enabled = true,
                nowElapsed = 100_000,
                heartbeatElapsed = 99_000,
                servicePid = 1200,
                keeperProcessPid = 1300
            )
        )
    }

    @Test
    fun `fresh heartbeat in service process does not require recovery`() {
        assertFalse(
            GuardianLiveness.shouldRecover(
                enabled = true,
                nowElapsed = 100_000,
                heartbeatElapsed = 99_000,
                servicePid = 1200,
                keeperProcessPid = 1200
            )
        )
    }

    @Test
    fun `new service receives startup grace before first heartbeat`() {
        assertFalse(
            GuardianLiveness.shouldRecover(
                enabled = true,
                nowElapsed = 105_000,
                heartbeatElapsed = 0,
                servicePid = 1200,
                keeperProcessPid = 1200,
                serviceStartedElapsed = 100_000
            )
        )
    }
}
