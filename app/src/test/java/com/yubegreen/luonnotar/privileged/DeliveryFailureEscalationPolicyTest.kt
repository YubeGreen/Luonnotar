package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryFailureEscalationPolicyTest {
    @Test
    fun requiresTwoSeparatedDeliveryEpisodes() {
        val now = 1_000_000L
        assertFalse(decide(now, listOf(now - 1_000L)).allowed)
        assertEquals(
            "same_delivery_episode",
            decide(now, listOf(now - 10_000L, now - 1_000L)).reason
        )
        assertTrue(decide(now, listOf(now - 60_000L, now - 1_000L)).allowed)
    }

    @Test
    fun rejectsUnsupportedPackagesAndRebuildLoops() {
        val unsupported = DeliveryFailureEscalationPolicy.decide(
            packageName = "com.google.android.gms",
            nowElapsed = 1_000_000L,
            deliveryEpisodes = listOf(900_000L, 990_000L),
            lastRebuildElapsed = 0L,
            rebuildHistory = emptyList()
        )
        assertEquals("unsupported_package", unsupported.reason)

        val cooldown = decide(
            now = 1_000_000L,
            episodes = listOf(900_000L, 990_000L),
            last = 950_000L
        )
        assertEquals("cooldown", cooldown.reason)
    }

    @Test
    fun episodeDebounceKeepsOneFailureAttemptFromCountingThreeLogLines() {
        assertTrue(DeliveryFailureEscalationPolicy.shouldRecordEpisode(null, 10_000L))
        assertFalse(DeliveryFailureEscalationPolicy.shouldRecordEpisode(10_000L, 15_000L))
        assertTrue(DeliveryFailureEscalationPolicy.shouldRecordEpisode(10_000L, 20_000L))
    }

    @Test
    fun criticalDeliveryFailureEscalatesAfterVerifiedThawExhaustion() {
        val decision = DeliveryFailureEscalationPolicy.decide(
            packageName = "com.whatsapp",
            nowElapsed = 1_000_000L,
            deliveryEpisodes = listOf(999_000L),
            lastRebuildElapsed = 0L,
            rebuildHistory = emptyList(),
            verifiedFrozenAfterBurst = true
        )
        assertTrue(decision.allowed)
        assertEquals("critical_delivery_failure_after_thaw_exhaustion", decision.reason)
    }

    private fun decide(
        now: Long,
        episodes: List<Long>,
        last: Long = 0L
    ) = DeliveryFailureEscalationPolicy.decide(
        packageName = "com.whatsapp",
        nowElapsed = now,
        deliveryEpisodes = episodes,
        lastRebuildElapsed = last,
        rebuildHistory = emptyList()
    )
}
