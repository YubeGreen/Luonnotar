package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsRecoveryPolicyTest {
    private fun decide(
        enabled: Boolean = true,
        manual: Boolean = false,
        now: Long = 1_000_000L,
        freezes: List<Long> = listOf(990_000L, 995_000L, 999_000L),
        last: Long = 0L,
        history: List<Long> = emptyList()
    ) = GmsRecoveryPolicy.decide(
        automaticEnabled = enabled,
        manual = manual,
        nowElapsed = now,
        freezeEvents = freezes,
        lastRecoveryElapsed = last,
        recoveryHistory = history,
        freezeThreshold = 3,
        freezeWindowMs = 10 * 60_000L,
        automaticCooldownMs = 6 * 60 * 60_000L,
        manualCooldownMs = 2 * 60_000L,
        maxRecoveriesPer24Hours = 2
    )

    @Test
    fun automaticRecoveryRequiresExplicitEnableAndEvidence() {
        assertEquals("automatic_recovery_disabled", decide(enabled = false).reason)
        assertEquals("insufficient_freeze_evidence", decide(freezes = listOf(999_000L)).reason)
        assertTrue(decide().allowed)
    }

    @Test
    fun manualRecoveryBypassesFreezeThresholdButNotCooldown() {
        assertTrue(decide(enabled = false, manual = true, freezes = emptyList()).allowed)
        val blocked = decide(manual = true, last = 950_000L)
        assertFalse(blocked.allowed)
        assertEquals("cooldown", blocked.reason)
    }

    @Test
    fun dailyLimitStopsRecoveryLoops() {
        val decision = decide(history = listOf(100_000L, 900_000L))
        assertFalse(decision.allowed)
        assertEquals("daily_limit", decision.reason)
    }

    @Test
    fun explicitTransportEvidenceUsesTheSameCooldownAndDailyLimits() {
        val allowed = GmsRecoveryPolicy.decide(
            automaticEnabled = true,
            manual = false,
            nowElapsed = 1_000_000L,
            freezeEvents = emptyList(),
            lastRecoveryElapsed = 0L,
            recoveryHistory = emptyList(),
            freezeThreshold = 3,
            freezeWindowMs = 10 * 60_000L,
            automaticCooldownMs = 6 * 60 * 60_000L,
            manualCooldownMs = 2 * 60_000L,
            maxRecoveriesPer24Hours = 2,
            automaticEvidenceReason = "mcs_missing_after_bad_auth"
        )
        assertTrue(allowed.allowed)
        assertEquals("mcs_missing_after_bad_auth", allowed.reason)
    }

    @Test
    fun elapsedClockResetDoesNotPermitImmediateRetry() {
        val decision = decide(last = 2_000_000L)
        assertFalse(decision.allowed)
        assertEquals("elapsed_clock_reset", decision.reason)
    }
}
