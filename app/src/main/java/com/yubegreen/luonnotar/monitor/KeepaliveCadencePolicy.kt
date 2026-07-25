package com.yubegreen.luonnotar.monitor

object KeepaliveCadencePolicy {
    const val NORMAL_INTERVAL_MS = 5 * 60_000L
    const val VIVO_SCREEN_OFF_INTERVAL_MS = 30_000L

    fun intervalMs(aggressiveMode: Boolean, screenInteractive: Boolean): Long =
        if (aggressiveMode && !screenInteractive) {
            VIVO_SCREEN_OFF_INTERVAL_MS
        } else {
            NORMAL_INTERVAL_MS
        }

    fun mayProbe(vpnPresent: Boolean, paused: Boolean): Boolean =
        vpnPresent && !paused
}
