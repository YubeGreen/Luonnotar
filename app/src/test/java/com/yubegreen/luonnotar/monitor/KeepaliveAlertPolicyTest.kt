package com.yubegreen.luonnotar.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepaliveAlertPolicyTest {
    @Test
    fun `two current failures alert without adb routing evidence`() {
        assertTrue(
            KeepaliveAlertPolicy.shouldAlertHttps(
                paused = false,
                vpnPresent = true,
                validated = true,
                lastAttemptElapsed = 100L,
                attemptEvidenceIsCurrent = true,
                failures = 2,
                hasAnySuccess = false,
                hasRecentSuccess = false
            )
        )
    }

    @Test
    fun `one failure preserves history without prematurely alerting`() {
        assertFalse(
            KeepaliveAlertPolicy.shouldAlertHttps(
                paused = false,
                vpnPresent = true,
                validated = true,
                lastAttemptElapsed = 100L,
                attemptEvidenceIsCurrent = true,
                failures = 1,
                hasAnySuccess = true,
                hasRecentSuccess = true
            )
        )
    }

    @Test
    fun `stale success alerts independently of top level state`() {
        assertTrue(
            KeepaliveAlertPolicy.shouldAlertHttps(
                paused = false,
                vpnPresent = true,
                validated = true,
                lastAttemptElapsed = 100L,
                attemptEvidenceIsCurrent = true,
                failures = 0,
                hasAnySuccess = true,
                hasRecentSuccess = false
            )
        )
    }
}
