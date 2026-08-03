package com.yubegreen.luonnotar.service

object PersistentHeartbeatBackoffPolicy {
    const val BASE_RETRY_DELAY_MS = 5_000L
    const val MAX_RETRY_DELAY_MS = 5 * 60_000L
    const val STABLE_CONNECTION_MS = 5 * 60_000L
    const val RECONNECT_STORM_WINDOW_MS = 10 * 60_000L
    const val RECONNECT_STORM_MAX_ATTEMPTS = 8
    const val RECONNECT_STORM_COOLDOWN_MS = 10 * 60_000L

    fun retryDelayMs(consecutiveFailures: Int): Long {
        val exponent = (consecutiveFailures.coerceAtLeast(1) - 1)
            .coerceAtMost(6)
        return (BASE_RETRY_DELAY_MS shl exponent)
            .coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    fun isReconnectStorm(
        attemptElapsedValues: Collection<Long>,
        nowElapsed: Long
    ): Boolean =
        attemptElapsedValues.count { attemptElapsed ->
            attemptElapsed > 0L &&
                nowElapsed - attemptElapsed <= RECONNECT_STORM_WINDOW_MS
        } >= RECONNECT_STORM_MAX_ATTEMPTS
}
