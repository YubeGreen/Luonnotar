package com.yubegreen.luonnotar.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepaliveCadencePolicyTest {
    @Test
    fun `probe requires a usable vpn and an unpaused guardian`() {
        assertTrue(
            KeepaliveCadencePolicy.mayProbe(
                vpnPresent = true,
                paused = false
            )
        )
        assertFalse(
            KeepaliveCadencePolicy.mayProbe(
                vpnPresent = false,
                paused = false
            )
        )
        assertFalse(
            KeepaliveCadencePolicy.mayProbe(
                vpnPresent = true,
                paused = true
            )
        )
    }
}
