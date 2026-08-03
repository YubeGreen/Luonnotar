package com.yubegreen.luonnotar.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class TailscaleGuardianStatePolicyTest {
    @Test
    fun targetRoutingWarningIsNotSoftenedByTailscaleOverlay() {
        assertEquals(
            GuardianState.TARGET_ROUTING_UNVERIFIED,
            TailscaleGuardianStatePolicy.overlay(
                base = GuardianState.TARGET_ROUTING_UNVERIFIED,
                tailscaleActive = true,
                tailscalePresent = true,
                complete = true,
                blocked = false,
                suspended = false,
                routeState = VpnRouteState.ROUTED,
                dnsEvidenceCurrent = true,
                dnsFailures = 0
            )
        )
    }
    @Test
    fun genericVpnBlockedOverridesHealthyPath() {
        assertEquals(
            GuardianState.VPN_BLOCKED,
            VpnGuardianStatePolicy.overlay(
                base = GuardianState.VPN_PATH_HEALTHY,
                vpnPresent = true,
                complete = true,
                blocked = true,
                notSuspended = true,
                routeState = VpnRouteState.ROUTED,
                dnsEvidenceCurrent = true,
                dnsFailures = 0
            )
        )
    }

    @Test
    fun genericVpnDnsFailuresAreIndependentFromHttps() {
        assertEquals(
            GuardianState.VPN_DNS_STALLED,
            VpnGuardianStatePolicy.overlay(
                base = GuardianState.VPN_PATH_HEALTHY,
                vpnPresent = true,
                complete = true,
                blocked = false,
                notSuspended = true,
                routeState = VpnRouteState.ROUTED,
                dnsEvidenceCurrent = true,
                dnsFailures = 2
            )
        )
    }

    @Test
    fun blockedTailscaleOverridesHealthyPath() {
        assertEquals(
            GuardianState.TAILSCALE_BLOCKED,
            TailscaleGuardianStatePolicy.overlay(
                base = GuardianState.VPN_PATH_HEALTHY,
                tailscaleActive = true,
                tailscalePresent = true,
                complete = true,
                blocked = true,
                suspended = false,
                routeState = VpnRouteState.ROUTED,
                dnsEvidenceCurrent = true,
                dnsFailures = 0
            )
        )
    }

    @Test
    fun twoCurrentDnsFailuresBecomeStalled() {
        assertEquals(
            GuardianState.TAILSCALE_DNS_STALLED,
            TailscaleGuardianStatePolicy.overlay(
                base = GuardianState.VPN_PATH_HEALTHY,
                tailscaleActive = true,
                tailscalePresent = true,
                complete = true,
                blocked = false,
                suspended = false,
                routeState = VpnRouteState.ROUTED,
                dnsEvidenceCurrent = true,
                dnsFailures = 2
            )
        )
    }

    @Test
    fun healthyTailscalePathDoesNotClaimGmsDelivery() {
        assertEquals(
            GuardianState.TAILSCALE_PATH_HEALTHY_GMS_UNKNOWN,
            TailscaleGuardianStatePolicy.overlay(
                base = GuardianState.VPN_PATH_HEALTHY,
                tailscaleActive = true,
                tailscalePresent = true,
                complete = true,
                blocked = false,
                suspended = false,
                routeState = VpnRouteState.ROUTED,
                dnsEvidenceCurrent = true,
                dnsFailures = 0
            )
        )
    }

    @Test
    fun pausedStateIsNeverOverridden() {
        assertEquals(
            GuardianState.PAUSED,
            TailscaleGuardianStatePolicy.overlay(
                base = GuardianState.PAUSED,
                tailscaleActive = false,
                tailscalePresent = true,
                complete = true,
                blocked = false,
                suspended = false,
                routeState = VpnRouteState.ROUTED,
                dnsEvidenceCurrent = false,
                dnsFailures = 0
            )
        )
    }
}
