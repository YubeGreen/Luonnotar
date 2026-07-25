package com.yubegreen.luonnotar.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmRecoveryPolicyTest {
    @Test
    fun `disabled paused or live service never starts`() {
        assertEquals(
            AlarmRecoveryPolicy.Action.NONE,
            AlarmRecoveryPolicy.decide(false, false, true, 35, true)
        )
        assertEquals(
            AlarmRecoveryPolicy.Action.NONE,
            AlarmRecoveryPolicy.decide(true, true, true, 35, true)
        )
        assertEquals(
            AlarmRecoveryPolicy.Action.NONE,
            AlarmRecoveryPolicy.decide(true, false, false, 35, true)
        )
    }

    @Test
    fun `exact alarm may recover foreground service`() {
        assertEquals(
            AlarmRecoveryPolicy.Action.START_FOREGROUND_SERVICE,
            AlarmRecoveryPolicy.decide(true, false, true, 35, true)
        )
    }

    @Test
    fun `inexact alarm on Android 12 requires user interaction`() {
        assertEquals(
            AlarmRecoveryPolicy.Action.REQUIRE_USER_INTERACTION,
            AlarmRecoveryPolicy.decide(true, false, true, 31, false)
        )
    }

    @Test
    fun `legacy platform may recover from ordinary alarm`() {
        assertEquals(
            AlarmRecoveryPolicy.Action.START_FOREGROUND_SERVICE,
            AlarmRecoveryPolicy.decide(true, false, true, 30, false)
        )
    }
}
