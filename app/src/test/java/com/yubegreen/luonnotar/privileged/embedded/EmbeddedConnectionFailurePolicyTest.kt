package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedConnectionFailurePolicyTest {
    @Test fun operationTimeoutWithLivePingDoesNotKillConnection() {
        assertFalse(EmbeddedConnectionFailurePolicy.shouldMarkDead(shortPingSucceeded = true))
    }

    @Test fun failedIndependentPingCanKillConnection() {
        assertTrue(EmbeddedConnectionFailurePolicy.shouldMarkDead(shortPingSucceeded = false))
    }
}
