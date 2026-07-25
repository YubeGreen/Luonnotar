package com.yubegreen.luonnotar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianTimelineSnapshotTest {
    @Test
    fun exportedTimelineSnapshotContainsEveryRequiredStateField() {
        val snapshot = GuardianTimelineSnapshot(
            wallTime = "2026-07-25T12:34:56Z",
            elapsedRealtime = 100L,
            screenInteractive = false,
            deviceIdleMode = true,
            powerSaveMode = true,
            wakeLockHeld = true,
            wifiLockHeld = true,
            networkHandle = 200L,
            vpnPresent = true,
            validated = true,
            underlay = "WIFI",
            probeInFlight = false,
            lastProbeAgeMs = 20L,
            lastProbeRttMs = 30L,
            timerDriftMs = 40L,
            serviceGeneration = 2L
        )
        val exported = snapshot.toMap()
        assertTrue(exported.keys.containsAll(GuardianTimelineSnapshot.REQUIRED_FIELDS))
        assertEquals(
            GuardianTimelineSnapshot.REQUIRED_FIELDS.size,
            exported.size
        )
    }
}
