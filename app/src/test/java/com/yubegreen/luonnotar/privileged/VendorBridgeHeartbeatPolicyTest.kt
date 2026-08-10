package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Test

class VendorBridgeHeartbeatPolicyTest {
    @Test
    fun freshFileCannotMaskDeadProtocol() {
        val decision = VendorBridgeHeartbeatPolicy.decide(
            alive = true,
            ready = true,
            protocolAgeMs = 7_300_000L,
            fileValid = true,
            fileAgeMs = 1_600L,
            staleMs = 30_000L
        )
        assertEquals(
            VendorBridgeHeartbeatPolicy.Action.RESTART_PROTOCOL_STALLED,
            decision.action
        )
        assertEquals("protocol_stalled_file_fresh", decision.reason)
    }

    @Test
    fun startupIsAllowedToReachReadyBeforeFileValidation() {
        val decision = VendorBridgeHeartbeatPolicy.decide(
            alive = true,
            ready = false,
            protocolAgeMs = Long.MAX_VALUE,
            fileValid = false,
            fileAgeMs = -1L,
            staleMs = 30_000L
        )
        assertEquals(VendorBridgeHeartbeatPolicy.Action.NONE, decision.action)
    }

    @Test
    fun staleFileRestartsOtherwiseHealthyReadyBridge() {
        val decision = VendorBridgeHeartbeatPolicy.decide(
            alive = true,
            ready = true,
            protocolAgeMs = 5_000L,
            fileValid = false,
            fileAgeMs = -1L,
            staleMs = 30_000L
        )
        assertEquals(
            VendorBridgeHeartbeatPolicy.Action.RESTART_FILE_STALE,
            decision.action
        )
    }
}
