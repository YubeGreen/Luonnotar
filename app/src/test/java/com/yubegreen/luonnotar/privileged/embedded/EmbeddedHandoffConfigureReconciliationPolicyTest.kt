package com.yubegreen.luonnotar.privileged.embedded

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedHandoffConfigureReconciliationPolicyTest {
    @Test fun readTimeoutIsEligibleForLateReconcile() {
        assertTrue(
            EmbeddedHandoffConfigureReconciliationPolicy.shouldAttemptLateReconcile(
                SocketTimeoutException("Read timed out")
            )
        )
    }

    @Test fun nonTimeoutFailureIsNotEligibleForLateReconcile() {
        assertFalse(
            EmbeddedHandoffConfigureReconciliationPolicy.shouldAttemptLateReconcile(
                IOException("broken transport")
            )
        )
    }

    @Test fun activationSnapshotIsNotFullConfigureEvidence() {
        assertFalse(
            EmbeddedHandoffConfigureReconciliationPolicy.isFullyConfiguredStatus(
                """{"running":true,"handoffActivation":true}"""
            )
        )
        assertTrue(
            EmbeddedHandoffConfigureReconciliationPolicy.isFullyConfiguredStatus(
                """{"running":true}"""
            )
        )
    }
}
