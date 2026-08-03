package com.yubegreen.luonnotar.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianCleanupPolicyTest {
    @Test fun disabledAllowsCancel() = assertTrue(GuardianCleanupPolicy.shouldCancelForDisabled(false))
    @Test fun reenabledRejectsOldDisabledCleanup() = assertFalse(GuardianCleanupPolicy.shouldCancelForDisabled(true))
    @Test fun pausedAllowsCancel() = assertTrue(GuardianCleanupPolicy.shouldCancelForPaused(true, true))
    @Test fun resumedRejectsOldPausedCleanup() = assertFalse(GuardianCleanupPolicy.shouldCancelForPaused(true, false))
}
