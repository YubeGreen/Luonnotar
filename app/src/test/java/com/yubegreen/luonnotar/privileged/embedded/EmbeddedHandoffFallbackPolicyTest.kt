package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedHandoffFallbackPolicyTest {
    @Test
    fun destructiveAdbFallbackIsLimitedToUnreachableOrLegacyRetirement() {
        assertTrue(
            EmbeddedGuardianManager.requiresAdbRestartFallback(
                EmbeddedGuardianManager.HandoffAttempt(false, "engine_unreachable")
            )
        )
        assertTrue(
            EmbeddedGuardianManager.requiresAdbRestartFallback(
                EmbeddedGuardianManager.HandoffAttempt(false, "identity_missing")
            )
        )
        assertTrue(
            EmbeddedGuardianManager.requiresAdbRestartFallback(
                EmbeddedGuardianManager.HandoffAttempt(false, "old_revision_retired_for_adb_restart")
            )
        )
        assertFalse(
            EmbeddedGuardianManager.requiresAdbRestartFallback(
                EmbeddedGuardianManager.HandoffAttempt(false, "handoff_request_failed")
            )
        )
        assertFalse(
            EmbeddedGuardianManager.requiresAdbRestartFallback(
                EmbeddedGuardianManager.HandoffAttempt(false, "handoff_verify_timeout")
            )
        )
        assertFalse(
            EmbeddedGuardianManager.requiresAdbRestartFallback(
                EmbeddedGuardianManager.HandoffAttempt(false, "self_update_handoff_in_progress")
            )
        )
    }
}
