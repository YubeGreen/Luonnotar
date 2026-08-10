package com.yubegreen.luonnotar.notification

internal object NotificationListenerFaultInjectionPolicy {
    const val DEFAULT_STICKY_DURATION_MS = 5 * 60_000L
    const val MIN_STICKY_DURATION_MS = 150_000L
    const val MAX_STICKY_DURATION_MS = 10 * 60_000L

    fun boundedDurationMs(requestedMs: Long): Long {
        val chosen =
            if (requestedMs > 0L) requestedMs
            else DEFAULT_STICKY_DURATION_MS
        return chosen.coerceIn(MIN_STICKY_DURATION_MS, MAX_STICKY_DURATION_MS)
    }

    fun isActive(nowElapsed: Long, untilElapsed: Long): Boolean =
        nowElapsed >= 0L && untilElapsed > nowElapsed

    fun remainingMs(nowElapsed: Long, untilElapsed: Long): Long =
        (untilElapsed - nowElapsed).coerceAtLeast(0L)
}
