package com.yubegreen.luonnotar.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyReadGateTest {
    @Test
    fun `short policy is immediately considered read`() {
        assertTrue(PolicyReadGate.isAtEnd(0, 900, 700, 12))
    }

    @Test
    fun `zero sized pre layout state never unlocks consent`() {
        assertFalse(PolicyReadGate.isAtEnd(0, 0, 0, 12))
    }

    @Test
    fun `long policy requires reaching the end`() {
        assertFalse(PolicyReadGate.isAtEnd(0, 700, 1_800, 12))
        assertTrue(PolicyReadGate.isAtEnd(1_090, 700, 1_800, 12))
    }
}
