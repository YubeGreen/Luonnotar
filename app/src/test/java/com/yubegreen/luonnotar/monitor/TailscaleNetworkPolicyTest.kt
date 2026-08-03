package com.yubegreen.luonnotar.monitor

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleNetworkPolicyTest {
    @Test
    fun recognizesIpv4Quad100Dns() {
        assertTrue(
            TailscaleNetworkPolicy.hasQuad100Dns(
                listOf(InetAddress.getByName("100.100.100.100"))
            )
        )
    }

    @Test
    fun recognizesIpv6Quad100Dns() {
        assertTrue(
            TailscaleNetworkPolicy.hasQuad100Dns(
                listOf(InetAddress.getByName("fd7a:115c:a1e0::53"))
            )
        )
    }

    @Test
    fun doesNotTreatOrdinaryDnsAsTailscale() {
        assertFalse(
            TailscaleNetworkPolicy.hasQuad100Dns(
                listOf(
                    InetAddress.getByName("1.1.1.1"),
                    InetAddress.getByName("2606:4700:4700::1111")
                )
            )
        )
    }
}
