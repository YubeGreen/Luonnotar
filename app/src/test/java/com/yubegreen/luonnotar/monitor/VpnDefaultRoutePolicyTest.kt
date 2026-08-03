package com.yubegreen.luonnotar.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnDefaultRoutePolicyTest {
    @Test
    fun tailscaleExitNodeStyleDualStackRoutesAreInternetRouted() {
        val evidence = VpnDefaultRoutePolicy.evaluate(
            listOf(
                VpnRouteDescriptor(VpnRouteFamily.IPV4, 0),
                VpnRouteDescriptor(VpnRouteFamily.IPV6, 0),
                VpnRouteDescriptor(VpnRouteFamily.IPV4, 32)
            )
        )

        assertTrue(evidence.internetRouted)
        assertTrue(evidence.ipv4DefaultRoute)
        assertTrue(evidence.ipv6DefaultRoute)
    }

    @Test
    fun tailnetOnlyRoutesAreNotMistakenForExitNode() {
        val evidence = VpnDefaultRoutePolicy.evaluate(
            listOf(
                VpnRouteDescriptor(VpnRouteFamily.IPV4, 10),
                VpnRouteDescriptor(VpnRouteFamily.IPV6, 48)
            )
        )

        assertFalse(evidence.internetRouted)
        assertFalse(evidence.ipv4DefaultRoute)
        assertFalse(evidence.ipv6DefaultRoute)
    }

    @Test
    fun singleStackDefaultRouteStillProvesThatStackUsesVpn() {
        val evidence = VpnDefaultRoutePolicy.evaluate(
            listOf(VpnRouteDescriptor(VpnRouteFamily.IPV4, 0))
        )

        assertTrue(evidence.internetRouted)
        assertTrue(evidence.ipv4DefaultRoute)
        assertFalse(evidence.ipv6DefaultRoute)
    }

    @Test
    fun pairedSplitDefaultsCoverBothAddressFamilies() {
        val evidence = VpnDefaultRoutePolicy.evaluate(
            listOf(
                VpnRouteDescriptor(VpnRouteFamily.IPV4, 1, "0.0.0.0"),
                VpnRouteDescriptor(VpnRouteFamily.IPV4, 1, "128.0.0.0"),
                VpnRouteDescriptor(VpnRouteFamily.IPV6, 1, "::"),
                VpnRouteDescriptor(VpnRouteFamily.IPV6, 1, "8000::")
            )
        )

        assertTrue(evidence.ipv4DefaultRoute)
        assertTrue(evidence.ipv6DefaultRoute)
    }

    @Test
    fun oneHalfOrUnusableRoutesDoNotProveDefaultCoverage() {
        val evidence = VpnDefaultRoutePolicy.evaluate(
            listOf(
                VpnRouteDescriptor(VpnRouteFamily.IPV4, 1, "0.0.0.0"),
                VpnRouteDescriptor(
                    VpnRouteFamily.IPV4,
                    1,
                    "128.0.0.0",
                    usable = false
                ),
                VpnRouteDescriptor(VpnRouteFamily.IPV6, 1, "::")
            )
        )

        assertFalse(evidence.ipv4DefaultRoute)
        assertFalse(evidence.ipv6DefaultRoute)
    }

    @Test
    fun transientUnknownPreservesSameHandleRouteEvidence() {
        val routed = VpnRouteObservation(
            VpnRouteState.ROUTED,
            ipv4DefaultRoute = true,
            ipv6DefaultRoute = true
        )
        val decision = VpnRouteStabilityPolicy.decide(
            previous = routed,
            observed = VpnRouteObservation(
                VpnRouteState.UNKNOWN,
                false,
                false
            ),
            sameVpnHandle = true,
            nowElapsed = 10_000L,
            routeLossCandidateElapsed = 0L
        )

        assertTrue(decision.evidence == routed)
        assertFalse(decision.scheduleRefresh)
    }

    @Test
    fun explicitRouteLossRequiresStableDebounce() {
        val routed = VpnRouteObservation(
            VpnRouteState.ROUTED,
            ipv4DefaultRoute = true,
            ipv6DefaultRoute = false
        )
        val lost = VpnRouteObservation(
            VpnRouteState.NOT_ROUTED,
            ipv4DefaultRoute = false,
            ipv6DefaultRoute = false
        )
        val first = VpnRouteStabilityPolicy.decide(
            routed,
            lost,
            sameVpnHandle = true,
            nowElapsed = 20_000L,
            routeLossCandidateElapsed = 0L
        )
        val confirmed = VpnRouteStabilityPolicy.decide(
            routed,
            lost,
            sameVpnHandle = true,
            nowElapsed =
                20_000L + VpnRouteStabilityPolicy.ROUTE_LOSS_DEBOUNCE_MS,
            routeLossCandidateElapsed = first.routeLossCandidateElapsed
        )

        assertTrue(first.evidence == routed)
        assertTrue(first.scheduleRefresh)
        assertTrue(confirmed.evidence == lost)
        assertFalse(confirmed.scheduleRefresh)
    }
}
