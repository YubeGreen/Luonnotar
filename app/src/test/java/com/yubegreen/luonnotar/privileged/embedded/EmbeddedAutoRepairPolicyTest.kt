package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedAutoRepairPolicyTest {
    @Test fun firstIdleFailureMayStartRepairImmediately() {
        assertTrue(
            EmbeddedAutoRepairPolicy.shouldDispatch(
                featureEnabled = true,
                setupState = EmbeddedSetupState.IDLE,
                nowElapsed = 5_000L,
                lastDispatchElapsed = 0L
            )
        )
    }

    @Test fun activeOrFailedSetupNeverRecursivelyStartsAnotherSession() {
        assertFalse(EmbeddedAutoRepairPolicy.shouldDispatch(true, EmbeddedSetupState.DISCOVERING, 100_000L, 0L))
        assertFalse(EmbeddedAutoRepairPolicy.shouldDispatch(true, EmbeddedSetupState.STARTING, 100_000L, 0L))
        assertFalse(EmbeddedAutoRepairPolicy.shouldDispatch(true, EmbeddedSetupState.FAILED, 100_000L, 0L))
    }

    @Test fun repeatedIdleRepairIsRateLimited() {
        assertFalse(
            EmbeddedAutoRepairPolicy.shouldDispatch(
                featureEnabled = true,
                setupState = EmbeddedSetupState.IDLE,
                nowElapsed = 100_000L,
                lastDispatchElapsed = 70_000L,
                cooldownMs = 60_000L
            )
        )
        assertTrue(
            EmbeddedAutoRepairPolicy.shouldDispatch(
                featureEnabled = true,
                setupState = EmbeddedSetupState.IDLE,
                nowElapsed = 130_000L,
                lastDispatchElapsed = 70_000L,
                cooldownMs = 60_000L
            )
        )
    }
}
