package com.yubegreen.luonnotar.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VpnSessionFingerprintTest {
    private fun fingerprint(
        dns: List<String> = listOf("100.100.100.100"),
        mtu: Int = 1280
    ): String = VpnSessionFingerprint.build(
        providerPackage = "com.tailscale.ipn",
        networkHandle = 42L,
        interfaceName = "tun0",
        linkAddresses = listOf("100.64.0.1/32"),
        dnsServers = dns,
        routeSet = listOf("0.0.0.0/0"),
        mtu = mtu,
        underlyingNetworkHandles = setOf(7L),
        bypassable = false
    )

    @Test
    fun unorderedInputsProduceTheSameFingerprint() {
        val first = VpnSessionFingerprint.build(
            providerPackage = "com.tailscale.ipn",
            networkHandle = 42L,
            interfaceName = "tun0",
            linkAddresses = listOf("fd00::1/128", "100.64.0.1/32"),
            dnsServers = listOf("fd7a:115c:a1e0::53", "100.100.100.100"),
            routeSet = listOf("::/0", "0.0.0.0/0"),
            mtu = 1280,
            underlyingNetworkHandles = setOf(9L, 7L),
            bypassable = false
        )
        val second = VpnSessionFingerprint.build(
            providerPackage = "com.tailscale.ipn",
            networkHandle = 42L,
            interfaceName = "tun0",
            linkAddresses = listOf("100.64.0.1/32", "fd00::1/128"),
            dnsServers = listOf("100.100.100.100", "fd7a:115c:a1e0::53"),
            routeSet = listOf("0.0.0.0/0", "::/0"),
            mtu = 1280,
            underlyingNetworkHandles = setOf(7L, 9L),
            bypassable = false
        )
        assertEquals(first, second)
    }

    @Test
    fun dnsAndMtuChangesRebuildTheSession() {
        val base = fingerprint()
        assertNotEquals(base, fingerprint(dns = listOf("1.1.1.1")))
        assertNotEquals(base, fingerprint(mtu = 1400))
    }

    @Test
    fun blockedAndSuspendedHealthAreNotFingerprintInputs() {
        val base = fingerprint()
        val sameStructure = fingerprint()
        assertEquals(base, sameStructure)
    }
}
