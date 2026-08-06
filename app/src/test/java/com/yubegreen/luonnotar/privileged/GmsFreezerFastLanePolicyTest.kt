package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsFreezerFastLanePolicyTest {
    @Test
    fun waitsThroughIntermediateStatesButAcceptsLaterVerifiedThaw() {
        assertFalse(GmsFreezerFastLanePolicy.isRecoveryReady("absent", 0))
        assertFalse(GmsFreezerFastLanePolicy.isRecoveryReady("frozen", 1))
        assertTrue(GmsFreezerFastLanePolicy.isRecoveryReady("thawed", 1))
    }

    @Test
    fun treatsAcceptedUnobservableAsProvisionalRecovery() {
        assertFalse(GmsFreezerFastLanePolicy.isRecoveryReady("unobservable", 0))
        assertTrue(GmsFreezerFastLanePolicy.isRecoveryReady("unobservable", 1))
        assertFalse(
            GmsFreezerFastLanePolicy.requiresKotlinFallback(
                state = "unobservable",
                acceptedCount = 1,
                exhausted = false
            )
        )
        assertTrue(
            GmsFreezerFastLanePolicy.requiresKotlinFallback(
                state = "unobservable",
                acceptedCount = 1,
                exhausted = true
            )
        )
    }

    @Test
    fun doesNotFallbackAfterVerifiedThawEvenIfBudgetWasPreviouslyHit() {
        assertFalse(
            GmsFreezerFastLanePolicy.requiresKotlinFallback(
                state = "thawed",
                acceptedCount = 1,
                exhausted = true
            )
        )
    }
}
