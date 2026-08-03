package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianActionPolicyTest {
    @Test
    fun newProcessIsActedOnImmediately() {
        assertTrue(
            GuardianActionPolicy.shouldReassert(
                previousPid = null,
                currentPid = 42,
                lastActionElapsed = null,
                nowElapsed = 1_000L,
                reassertIntervalMs = 60_000L
            )
        )
    }

    @Test
    fun pidReplacementIsActedOnImmediately() {
        assertTrue(
            GuardianActionPolicy.shouldReassert(
                previousPid = 41,
                currentPid = 42,
                lastActionElapsed = 900L,
                nowElapsed = 1_000L,
                reassertIntervalMs = 60_000L
            )
        )
    }

    @Test
    fun stablePidWaitsUntilReassertInterval() {
        assertFalse(
            GuardianActionPolicy.shouldReassert(
                previousPid = 42,
                currentPid = 42,
                lastActionElapsed = 1_000L,
                nowElapsed = 60_999L,
                reassertIntervalMs = 60_000L
            )
        )
        assertTrue(
            GuardianActionPolicy.shouldReassert(
                previousPid = 42,
                currentPid = 42,
                lastActionElapsed = 1_000L,
                nowElapsed = 61_000L,
                reassertIntervalMs = 60_000L
            )
        )
    }

    @Test
    fun elapsedClockResetForcesReassert() {
        assertTrue(
            GuardianActionPolicy.shouldReassert(
                previousPid = 42,
                currentPid = 42,
                lastActionElapsed = 5_000L,
                nowElapsed = 1_000L,
                reassertIntervalMs = 60_000L
            )
        )
    }
}
