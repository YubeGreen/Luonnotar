package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationListenerShellGuardianPolicyTest {
    @Test fun healthyListenerDoesNothing() {
        val d = NotificationListenerShellGuardianPolicy.decide(
            true, true, true, 300_000L, 100_000L, 100_000L, 0L, 120_000L, 900_000L
        )
        assertEquals(NotificationListenerShellGuardianPolicy.Action.NONE, d.action)
    }

    @Test fun firstFailureUsesOrdinaryRebind() {
        val d = NotificationListenerShellGuardianPolicy.decide(
            true, true, false, 300_000L, 0L, 0L, 0L, 120_000L, 900_000L
        )
        assertEquals(NotificationListenerShellGuardianPolicy.Action.ORDINARY_REBIND, d.action)
    }

    @Test fun stalledOrdinaryRebindEscalatesAfterTwoMinutes() {
        val d = NotificationListenerShellGuardianPolicy.decide(
            true, true, false, 230_000L, 100_000L, 100_000L, 0L, 120_000L, 900_000L
        )
        assertEquals(NotificationListenerShellGuardianPolicy.Action.STRONG_REREGISTER, d.action)
    }

    @Test fun strongRecoveryRespectsCooldown() {
        val d = NotificationListenerShellGuardianPolicy.decide(
            true, true, false, 300_000L, 100_000L, 100_000L, 250_000L, 120_000L, 900_000L
        )
        assertEquals(NotificationListenerShellGuardianPolicy.Action.NONE, d.action)
        assertEquals("strong_recovery_cooldown", d.reason)
    }

    @Test fun revokedSystemAccessNeverGetsToggled() {
        val d = NotificationListenerShellGuardianPolicy.decide(
            true, false, false, 500_000L, 100_000L, 100_000L, 0L, 120_000L, 900_000L
        )
        assertEquals(NotificationListenerShellGuardianPolicy.Action.NONE, d.action)
        assertEquals("system_access_not_authorized", d.reason)
    }
}
