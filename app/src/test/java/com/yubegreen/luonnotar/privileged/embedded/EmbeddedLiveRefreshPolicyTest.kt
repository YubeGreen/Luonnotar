package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedLiveRefreshPolicyTest {
    @Test fun defersWhileSetupOwnsTheEndpoint() {
        assertTrue(EmbeddedLiveRefreshPolicy.shouldDefer(EmbeddedSetupState.DISCOVERING))
        assertTrue(EmbeddedLiveRefreshPolicy.shouldDefer(EmbeddedSetupState.WAITING_PAIRING_CODE))
        assertTrue(EmbeddedLiveRefreshPolicy.shouldDefer(EmbeddedSetupState.STARTING))
    }

    @Test fun allowsIdleAndFailureRecoveryChecks() {
        assertFalse(EmbeddedLiveRefreshPolicy.shouldDefer(EmbeddedSetupState.IDLE))
        assertFalse(EmbeddedLiveRefreshPolicy.shouldDefer(EmbeddedSetupState.FAILED))
    }
}
