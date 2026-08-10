package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationListenerShellGuardianCadenceTest {
    @Test fun firstProbeRunsImmediately() {
        assertTrue(NotificationListenerShellGuardianPolicy.shouldProbe(10_000L, 0L))
    }

    @Test fun probeIsSuppressedBeforeThirtySeconds() {
        assertFalse(NotificationListenerShellGuardianPolicy.shouldProbe(39_999L, 10_000L))
    }

    @Test fun probeRunsAtThirtySeconds() {
        assertTrue(NotificationListenerShellGuardianPolicy.shouldProbe(40_000L, 10_000L))
    }

    @Test fun elapsedClockRollbackFailsOpenToProbe() {
        assertTrue(NotificationListenerShellGuardianPolicy.shouldProbe(5_000L, 10_000L))
    }
}
