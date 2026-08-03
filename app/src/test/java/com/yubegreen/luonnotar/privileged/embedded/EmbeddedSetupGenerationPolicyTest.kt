package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSetupGenerationPolicyTest {
    @Test fun repeatedTapReusesActiveSetupGeneration() {
        assertTrue(EmbeddedSetupGenerationPolicy.shouldReuseGeneration(true, EmbeddedSetupState.DISCOVERING))
        assertTrue(EmbeddedSetupGenerationPolicy.shouldReuseGeneration(true, EmbeddedSetupState.WAITING_PAIRING_CODE))
        assertTrue(EmbeddedSetupGenerationPolicy.shouldReuseGeneration(true, EmbeddedSetupState.STARTING))
    }

    @Test fun idleFailedOrDisabledSetupStartsNewGeneration() {
        assertFalse(EmbeddedSetupGenerationPolicy.shouldReuseGeneration(true, EmbeddedSetupState.IDLE))
        assertFalse(EmbeddedSetupGenerationPolicy.shouldReuseGeneration(true, EmbeddedSetupState.FAILED))
        assertFalse(EmbeddedSetupGenerationPolicy.shouldReuseGeneration(false, EmbeddedSetupState.STARTING))
    }
}
