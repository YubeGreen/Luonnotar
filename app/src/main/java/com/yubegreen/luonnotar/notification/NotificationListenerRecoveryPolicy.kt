package com.yubegreen.luonnotar.notification

object NotificationListenerRecoveryPolicy {
    const val STALE_HEARTBEAT_MS = 150_000L
    const val REBIND_COOLDOWN_MS = 60_000L

    fun shouldRequestRebind(
        nowElapsed: Long,
        connected: Boolean,
        heartbeatElapsed: Long,
        lastRequestElapsed: Long
    ): Boolean {
        val stale = !connected ||
            heartbeatElapsed <= 0L ||
            nowElapsed - heartbeatElapsed >= STALE_HEARTBEAT_MS
        if (!stale) return false
        return lastRequestElapsed <= 0L ||
            nowElapsed - lastRequestElapsed >= REBIND_COOLDOWN_MS
    }
}
