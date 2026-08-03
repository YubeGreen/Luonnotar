package com.yubegreen.luonnotar.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardRestartDispatchPolicyTest {
    @Test
    fun `old process cannot consume its own recovery alarm`() {
        assertEquals(
            HardRestartDispatchAction.RESCHEDULE_OLD_PID,
            HardRestartDispatchPolicy.decide(true, 120, 120)
        )
    }

    @Test
    fun `new keeper may start only for matching metadata`() {
        assertEquals(
            HardRestartDispatchAction.START_NEW_KEEPER,
            HardRestartDispatchPolicy.decide(true, 121, 120)
        )
        assertEquals(
            HardRestartDispatchAction.REJECT,
            HardRestartDispatchPolicy.decide(false, 121, 120)
        )
    }

    @Test
    fun `hard restart retry stops after three attempts or fifteen seconds`() {
        assertTrue(HardRestartRetryPolicy.maySchedule(1, 0, 3, 15_000))
        assertTrue(HardRestartRetryPolicy.maySchedule(3, 15_000, 3, 15_000))
        assertFalse(HardRestartRetryPolicy.maySchedule(4, 4_000, 3, 15_000))
        assertFalse(HardRestartRetryPolicy.maySchedule(2, 15_001, 3, 15_000))
    }
}
