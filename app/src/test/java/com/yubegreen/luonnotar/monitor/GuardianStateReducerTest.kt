package com.yubegreen.luonnotar.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianStateReducerTest {
    @Test
    fun `vpn loss wins and blocks application keepalive`() {
        assertEquals(
            GuardianState.VPN_LOST,
            GuardianStateReducer.reduce(
                enabled = true,
                paused = false,
                vpn = false,
                validated = false,
                bypassable = null,
                lastHttpCode = -1,
                failures = 0,
                hasAnySuccess = false,
                hasRecentSuccess = true,
                targetRoutingVerified = true,
                hasEverObservedVpn = true
            )
        )
        assertFalse(VpnOnlyRoutingPolicy.maySendHttps(defaultNetworkIsVpn = false, paused = false))
    }

    @Test
    fun `known bypassable vpn is never healthy`() {
        assertEquals(
            GuardianState.VPN_BYPASSABLE,
            GuardianStateReducer.reduce(
                true, false, true, true, true, 204, 0, false, false, true, true
            )
        )
    }

    @Test
    fun `three failures degrade a non bypassable vpn`() {
        assertEquals(
            GuardianState.KEEPALIVE_DEGRADED,
            GuardianStateReducer.reduce(
                true, false, true, true, false, 500, 3, true, true, true, true
            )
        )
    }

    @Test
    fun `healthy requires vpn route and successful keepalive`() {
        assertEquals(
            GuardianState.VPN_PATH_HEALTHY,
            GuardianStateReducer.reduce(
                true, false, true, true, false, 204, 0, true, true, true, true
            )
        )
        assertTrue(VpnOnlyRoutingPolicy.maySendHttps(defaultNetworkIsVpn = true, paused = false))
        assertFalse(VpnOnlyRoutingPolicy.maySendHttps(defaultNetworkIsVpn = true, paused = true))
    }

    @Test
    fun `unknown bypassability stays visibly unverified`() {
        assertEquals(
            GuardianState.LOCKDOWN_UNVERIFIED,
            GuardianStateReducer.reduce(
                true, false, true, true, null, 204, 0, true, true, true, true
            )
        )
    }

    @Test
    fun `vpn without any 204 success is explicit no evidence`() {
        assertEquals(
            GuardianState.NO_SUCCESS_EVIDENCE,
            GuardianStateReducer.reduce(
                true, false, true, true, false, 500, 1, false, false, true, true
            )
        )
    }

    @Test
    fun `paused and unvalidated states can never be healthy`() {
        assertEquals(
            GuardianState.PAUSED,
            GuardianStateReducer.reduce(
                true, true, true, true, false, 204, 0, true, true, true, true
            )
        )
        assertEquals(
            GuardianState.VPN_UNVALIDATED,
            GuardianStateReducer.reduce(
                true, false, true, false, false, 204, 0, true, true, true, true
            )
        )
    }

    @Test
    fun `latest request must be a clean 204`() {
        assertEquals(
            GuardianState.KEEPALIVE_DEGRADED,
            GuardianStateReducer.reduce(
                true, false, true, true, false, 500, 1, true, true, true, true
            )
        )
    }

    @Test
    fun `target uid routing is an independent diagnostic dimension`() {
        assertEquals(
            GuardianState.VPN_PATH_HEALTHY,
            GuardianStateReducer.reduce(
                true, false, true, true, false, 204, 0, true, true, false, true
            )
        )
    }

    @Test
    fun `first startup waits for vpn instead of reporting a loss`() {
        assertEquals(
            GuardianState.WAITING_FOR_VPN,
            GuardianStateReducer.reduce(
                true, false, false, false, null, -1, 0, false, false, false, false
            )
        )
    }
}
