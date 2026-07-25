package com.yubegreen.luonnotar.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbVpnEvidencePolicyTest {
    @Test
    fun `fresh evidence bound to boot and network is current`() {
        assertTrue(
            AdbVpnEvidencePolicy.isCurrent(
                verifiedElapsed = 100_000L,
                nowElapsed = 101_000L,
                verifiedBootId = "boot-a",
                currentBootId = "boot-a",
                activePackage = "ch.protonvpn.android",
                evidenceHash = "abc123",
                verifiedNetworkHandle = 42L,
                currentNetworkHandle = 42L,
                vpnPresent = true
            )
        )
    }

    @Test
    fun `evidence is invalid after vpn switch or loss`() {
        assertFalse(
            current(
                verifiedNetworkHandle = 41L,
                currentNetworkHandle = 42L,
                vpnPresent = true
            )
        )
        assertFalse(
            current(
                verifiedNetworkHandle = 42L,
                currentNetworkHandle = 42L,
                vpnPresent = false
            )
        )
    }

    @Test
    fun `evidence is invalid after reboot or expiry`() {
        assertFalse(current(verifiedBootId = "old-boot", currentBootId = "new-boot"))
        assertFalse(
            current(
                verifiedElapsed = 100_000L,
                nowElapsed = 100_000L + AdbVpnEvidencePolicy.MAX_AGE_MS + 1L
            )
        )
    }

    private fun current(
        verifiedElapsed: Long = 100_000L,
        nowElapsed: Long = 101_000L,
        verifiedBootId: String = "boot-a",
        currentBootId: String = "boot-a",
        verifiedNetworkHandle: Long = 42L,
        currentNetworkHandle: Long = 42L,
        vpnPresent: Boolean = true
    ) = AdbVpnEvidencePolicy.isCurrent(
        verifiedElapsed = verifiedElapsed,
        nowElapsed = nowElapsed,
        verifiedBootId = verifiedBootId,
        currentBootId = currentBootId,
        activePackage = "ch.protonvpn.android",
        evidenceHash = "abc123",
        verifiedNetworkHandle = verifiedNetworkHandle,
        currentNetworkHandle = currentNetworkHandle,
        vpnPresent = vpnPresent
    )
}
