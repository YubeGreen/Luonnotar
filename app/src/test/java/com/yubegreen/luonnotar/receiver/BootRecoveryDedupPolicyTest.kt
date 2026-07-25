package com.yubegreen.luonnotar.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootRecoveryDedupPolicyTest {
    @Test
    fun sameBootBroadcastWithinWindowIsDeduplicated() {
        val decision = BootRecoveryDedupPolicy.decide(
            action = "android.intent.action.BOOT_COMPLETED",
            currentBootId = "boot-a",
            nowElapsed = 29_999L,
            lastBootId = "boot-a",
            lastAcceptedElapsed = 5_000L,
            lastDispatchAccepted = true
        )
        assertTrue(decision.deduplicated)
    }

    @Test
    fun sameBootBroadcastAfterWindowIsAccepted() {
        val decision = BootRecoveryDedupPolicy.decide(
            action = "android.intent.action.USER_UNLOCKED",
            currentBootId = "boot-a",
            nowElapsed = 30_001L,
            lastBootId = "boot-a",
            lastAcceptedElapsed = 5_000L,
            lastDispatchAccepted = true
        )
        assertFalse(decision.deduplicated)
    }

    @Test
    fun differentBootNeverReusesElapsedRealtimeClaim() {
        val decision = BootRecoveryDedupPolicy.decide(
            action = "android.intent.action.BOOT_COMPLETED",
            currentBootId = "boot-b",
            nowElapsed = 1_000L,
            lastBootId = "boot-a",
            lastAcceptedElapsed = 900L,
            lastDispatchAccepted = true
        )
        assertFalse(decision.deduplicated)
    }

    @Test
    fun failedDispatchAllowsNextBootBroadcastToRetry() {
        val decision = BootRecoveryDedupPolicy.decide(
            action = "android.intent.action.BOOT_COMPLETED",
            currentBootId = "boot-a",
            nowElapsed = 6_000L,
            lastBootId = "boot-a",
            lastAcceptedElapsed = 5_000L,
            lastDispatchAccepted = false
        )
        assertFalse(decision.deduplicated)
    }

    @Test
    fun packageReplacementIsIndependentFromBootDeduplication() {
        val decision = BootRecoveryDedupPolicy.decide(
            action = "android.intent.action.MY_PACKAGE_REPLACED",
            currentBootId = "boot-a",
            nowElapsed = 6_000L,
            lastBootId = "boot-a",
            lastAcceptedElapsed = 5_000L,
            lastDispatchAccepted = true
        )
        assertFalse(decision.deduplicated)
    }

    @Test
    fun deduplicatedUnlockedBroadcastStillRequiresUnlockedMaintenance() {
        val decision = BootRecoveryDedupPolicy.decide(
            action = "android.intent.action.USER_UNLOCKED",
            currentBootId = "boot-a",
            nowElapsed = 6_000L,
            lastBootId = "boot-a",
            lastAcceptedElapsed = 5_000L,
            lastDispatchAccepted = true
        )

        assertTrue(decision.deduplicated)
        assertTrue(
            BootRecoveryDedupPolicy.requiresUnlockedMaintenance(
                "android.intent.action.USER_UNLOCKED"
            )
        )
        assertFalse(
            BootRecoveryDedupPolicy.requiresUnlockedMaintenance(
                "android.intent.action.LOCKED_BOOT_COMPLETED"
            )
        )
    }
}
