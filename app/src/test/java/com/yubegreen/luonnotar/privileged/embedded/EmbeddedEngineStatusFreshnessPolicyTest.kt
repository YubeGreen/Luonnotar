package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedEngineStatusFreshnessPolicyTest {
    @Test fun acceptsRecentSnapshot() {
        assertTrue(
            EmbeddedEngineStatusFreshnessPolicy.isFresh(
                nowElapsed = 100_000L,
                snapshotElapsed = 60_000L
            )
        )
    }

    @Test fun rejectsStaleMissingAndFutureSnapshot() {
        assertFalse(
            EmbeddedEngineStatusFreshnessPolicy.isFresh(
                nowElapsed = 100_001L,
                snapshotElapsed = 55_000L
            )
        )
        assertFalse(EmbeddedEngineStatusFreshnessPolicy.isFresh(100_000L, 0L))
        assertFalse(EmbeddedEngineStatusFreshnessPolicy.isFresh(10_000L, 20_000L))
        assertEquals(Long.MAX_VALUE, EmbeddedEngineStatusFreshnessPolicy.ageMs(10_000L, 20_000L))
    }
}
