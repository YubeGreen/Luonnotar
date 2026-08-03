package com.yubegreen.luonnotar.monitor

object KeepaliveCadencePolicy {
    const val NORMAL_INTERVAL_MS = 5 * 60_000L

    fun mayProbe(vpnPresent: Boolean, paused: Boolean): Boolean =
        vpnPresent && !paused
}
