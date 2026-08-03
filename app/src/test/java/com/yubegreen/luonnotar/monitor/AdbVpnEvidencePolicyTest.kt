package com.yubegreen.luonnotar.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
                vpnPresent = true,
                verifiedSessionFingerprint = "session-a",
                currentSessionFingerprint = "session-a"
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
    fun `evidence is invalid after reboot and only stale after one hour`() {
        assertFalse(current(verifiedBootId = "old-boot", currentBootId = "new-boot"))
        assertEquals(
            AdbVpnEvidencePolicy.Freshness.STALE,
            AdbVpnEvidencePolicy.freshness(
                verifiedElapsed = 100_000L,
                nowElapsed = 100_000L + 24 * 60 * 60_000L,
                verifiedBootId = "boot-a",
                currentBootId = "boot-a",
                activePackage = "ch.protonvpn.android",
                evidenceHash = "abc123",
                verifiedNetworkHandle = 42L,
                currentNetworkHandle = 42L,
                vpnPresent = true,
                verifiedSessionFingerprint = "session-a",
                currentSessionFingerprint = "session-a"
            )
        )
    }

    @Test
    fun `known provider change invalidates evidence`() {
        assertFalse(
            AdbVpnEvidencePolicy.isCurrent(
                verifiedElapsed = 100_000L,
                nowElapsed = 101_000L,
                verifiedBootId = "boot-a",
                currentBootId = "boot-a",
                activePackage = "com.tailscale.ipn",
                evidenceHash = "abc123",
                verifiedNetworkHandle = 42L,
                currentNetworkHandle = 42L,
                vpnPresent = true,
                verifiedSessionFingerprint = "session-a",
                currentSessionFingerprint = "session-a",
                currentProviderPackage = "ch.protonvpn.android"
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
        vpnPresent: Boolean = true,
        verifiedSessionFingerprint: String = "session-a",
        currentSessionFingerprint: String = "session-a"
    ) = AdbVpnEvidencePolicy.isCurrent(
        verifiedElapsed = verifiedElapsed,
        nowElapsed = nowElapsed,
        verifiedBootId = verifiedBootId,
        currentBootId = currentBootId,
        activePackage = "ch.protonvpn.android",
        evidenceHash = "abc123",
        verifiedNetworkHandle = verifiedNetworkHandle,
        currentNetworkHandle = currentNetworkHandle,
        vpnPresent = vpnPresent,
        verifiedSessionFingerprint = verifiedSessionFingerprint,
        currentSessionFingerprint = currentSessionFingerprint
    )

    @Test
    fun `session fingerprint change invalidates uid routing evidence`() {
        assertFalse(
            current(
                verifiedSessionFingerprint = "session-a",
                currentSessionFingerprint = "session-b"
            )
        )
    }
}
