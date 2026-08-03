package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsRestartVerifierTest {
    @Test
    fun replacementRequiresOldPidToDisappearAndNewPidToAppear() {
        val observation = GmsRestartVerifier.observe(
            oldPids = listOf(14026, 4291),
            currentPids = listOf(10458, 11000)
        )
        assertTrue(observation.restarted)
        assertEquals(listOf(10458, 11000), observation.newPids)
    }

    @Test
    fun unchangedOldPidIsNotAcceptedAsRecovery() {
        val observation = GmsRestartVerifier.observe(
            oldPids = listOf(14026),
            currentPids = listOf(14026, 10458)
        )
        assertFalse(observation.restarted)
        assertTrue(observation.oldPidStillAlive)
    }

    @Test
    fun emptyCurrentSetIsOnlyAStoppedPackageNotARestart() {
        val observation = GmsRestartVerifier.observe(
            oldPids = listOf(14026),
            currentPids = emptyList()
        )
        assertFalse(observation.restarted)
        assertEquals(emptyList<Int>(), observation.newPids)
    }
}
