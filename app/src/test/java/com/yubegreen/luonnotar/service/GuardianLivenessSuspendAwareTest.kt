package com.yubegreen.luonnotar.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianLivenessSuspendAwareTest {
    private val common = mapOf(
        "enabled" to true,
        "servicePid" to 10,
        "keeperProcessPid" to 10,
        "serviceStartedElapsed" to 1_000L,
        "thresholdMs" to 90_000L
    )

    private fun check(nowElapsed: Long, heartbeat: Long, nowUptime: Long, lastUptime: Long): Boolean =
        GuardianLiveness.shouldRecover(
            enabled = true,
            nowElapsed = nowElapsed,
            heartbeatElapsed = heartbeat,
            servicePid = 10,
            keeperProcessPid = 10,
            nowUptime = nowUptime,
            lastTickUptime = lastUptime,
            serviceStartedElapsed = 0L,
            thresholdMs = 90_000L
        )

    @Test fun elapsedSleepDoesNotRecover() = assertFalse(check(601_000, 1_000, 105_000, 100_000))
    @Test fun uptimeStaleRecovers() = assertTrue(check(601_000, 1_000, 220_000, 100_000))
    @Test fun pidMismatchRecoversEvenWithFreshUptime() = assertTrue(
        GuardianLiveness.shouldRecover(true, 10_000, 9_000, 10, 11, 10_000, 9_000, 0, 90_000)
    )
    @Test fun futureUptimeFallsBackToElapsed() = assertTrue(check(200_000, 0, 100_000, 101_000))
    @Test fun zeroUptimeFallsBackToElapsed() = assertTrue(check(200_000, 0, 100_000, 0))
    @Test fun startupGraceSuppressesRecovery() = assertFalse(
        GuardianLiveness.shouldRecover(true, 5_000, 0, 10, 10, 1_000, 100_000, 1_000, 90_000)
    )
}
