package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPolicyCapabilityResultTest {
    @Test
    fun unsupportedCapabilityDoesNotCreateWholeTargetFailure() {
        val result = BackgroundPolicyCapabilityResult(
            name = "hibernation_disabled",
            supported = false,
            applied = false,
            verified = false,
            detail = "unknown command"
        )
        assertTrue(result.policySatisfied)
    }

    @Test
    fun supportedButRejectedCapabilityRemainsFailure() {
        val result = BackgroundPolicyCapabilityResult(
            name = "standby_active",
            supported = true,
            applied = false,
            verified = false,
            detail = "permission denied"
        )
        assertFalse(result.policySatisfied)
    }
}
