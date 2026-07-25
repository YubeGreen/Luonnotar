package com.yubegreen.luonnotar.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiUnderlayLockPolicyTest {
    @Test
    fun unknownUnderlayDoesNotImmediatelyReleaseWifiLock() {
        val history = WifiUnderlayHistory(
            lastExplicitUnderlay = "WIFI",
            lastWifiSeenElapsed = 10_000L
        )
        val decision = WifiUnderlayLockPolicy.decide(
            guardianActive = true,
            observedUnderlay = "UNDERLAY_UNKNOWN",
            nowElapsed = 20_000L,
            lockCurrentlyHeld = true,
            history = history
        )
        assertTrue(decision.shouldHoldLock)
        assertTrue(decision.unknownDurationMs >= 0L)
    }

    @Test
    fun explicitCellularUnderlayReleasesWifiLock() {
        val decision = WifiUnderlayLockPolicy.decide(
            guardianActive = true,
            observedUnderlay = "CELLULAR",
            nowElapsed = 20_000L,
            lockCurrentlyHeld = true,
            history = WifiUnderlayHistory(
                lastExplicitUnderlay = "WIFI",
                lastWifiSeenElapsed = 10_000L
            )
        )
        assertFalse(decision.shouldHoldLock)
        assertEquals(0L, decision.history.lastWifiSeenElapsed)
    }

    @Test
    fun sameBootProcessRebuildRestoresWifiHistoryForUnknownResult() {
        val restored = WifiUnderlayLockPolicy.restoreHistory(
            storedHistory = WifiUnderlayHistory(
                lastExplicitUnderlay = "WIFI",
                lastWifiSeenElapsed = 100_000L,
                unknownSinceElapsed = 105_000L
            ),
            storedBootId = "boot-a",
            runtimeBootId = "boot-a",
            currentBootId = "boot-a",
            nowElapsed = 110_000L
        )
        val decision = WifiUnderlayLockPolicy.decide(
            guardianActive = true,
            observedUnderlay = "UNDERLAY_UNKNOWN",
            nowElapsed = 110_000L,
            lockCurrentlyHeld = false,
            history = restored
        )

        assertEquals("WIFI", restored.lastExplicitUnderlay)
        assertTrue(decision.shouldHoldLock)
    }

    @Test
    fun deviceRebootRejectsElapsedRealtimeWifiHistory() {
        val restored = WifiUnderlayLockPolicy.restoreHistory(
            storedHistory = WifiUnderlayHistory(
                lastExplicitUnderlay = "WIFI",
                lastWifiSeenElapsed = 100_000L,
                unknownSinceElapsed = 105_000L
            ),
            storedBootId = "boot-a",
            runtimeBootId = "boot-b",
            currentBootId = "boot-b",
            nowElapsed = 10_000L
        )

        assertEquals(WifiUnderlayHistory(), restored)
    }

    @Test
    fun futureElapsedValuesAreRejectedDuringRestore() {
        val restored = WifiUnderlayLockPolicy.restoreHistory(
            storedHistory = WifiUnderlayHistory(
                lastExplicitUnderlay = "WIFI",
                lastWifiSeenElapsed = 20_000L,
                unknownSinceElapsed = 30_000L
            ),
            storedBootId = "boot-a",
            runtimeBootId = "boot-a",
            currentBootId = "boot-a",
            nowElapsed = 10_000L
        )

        assertEquals("WIFI", restored.lastExplicitUnderlay)
        assertEquals(0L, restored.lastWifiSeenElapsed)
        assertEquals(0L, restored.unknownSinceElapsed)
    }
}
