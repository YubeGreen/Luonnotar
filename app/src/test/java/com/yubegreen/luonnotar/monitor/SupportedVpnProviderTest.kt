package com.yubegreen.luonnotar.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedVpnProviderTest {
    @Test
    fun recognizesProtonAndTailscalePackages() {
        assertEquals(
            SupportedVpnProvider.PROTON,
            SupportedVpnProvider.fromPackage("ch.protonvpn.android")
        )
        assertEquals(
            SupportedVpnProvider.TAILSCALE,
            SupportedVpnProvider.fromPackage("com.tailscale.ipn")
        )
        assertTrue(SupportedVpnProvider.isSupported("com.tailscale.ipn"))
    }

    @Test
    fun rejectsAnUnrelatedVpnPackage() {
        assertNull(SupportedVpnProvider.fromPackage("com.example.other.vpn"))
    }
}
