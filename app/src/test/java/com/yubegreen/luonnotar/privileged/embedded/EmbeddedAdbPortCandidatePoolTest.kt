package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedAdbPortCandidatePoolTest {
    @Test fun refusedFirstCandidateFallsThroughToSecondCandidate() {
        val pool = EmbeddedAdbPortCandidatePool(cooldownMs = 10_000L)
        assertTrue(pool.offer(46_075, 1_000L))
        assertEquals(46_075, pool.next(1_001L).port)

        pool.markEndpointFailure(46_075, 1_100L)
        assertTrue(pool.offer(40_483, 1_200L))
        assertEquals(40_483, pool.next(1_201L).port)
    }

    @Test fun repeatedAdvertisementDoesNotClearFailureCooldown() {
        val pool = EmbeddedAdbPortCandidatePool(cooldownMs = 10_000L)
        pool.offer(46_075, 1_000L)
        pool.markEndpointFailure(46_075, 1_100L)

        assertFalse(pool.offer(46_075, 1_200L))
        val selection = pool.next(1_300L)
        assertNull(selection.port)
        assertEquals(9_800L, selection.retryAfterMs)
    }

    @Test fun cooledCandidateBecomesEligibleAgain() {
        val pool = EmbeddedAdbPortCandidatePool(cooldownMs = 10_000L)
        pool.offer(46_075, 1_000L)
        pool.markEndpointFailure(46_075, 1_100L)

        assertEquals(46_075, pool.next(11_100L).port)
    }
}
