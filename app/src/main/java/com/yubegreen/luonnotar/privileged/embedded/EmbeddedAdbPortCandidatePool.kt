package com.yubegreen.luonnotar.privileged.embedded

/**
 * Keeps every mDNS-advertised local ADB endpoint instead of allowing the first callback to win.
 * A refused/stale endpoint is cooled down while newly advertised endpoints remain immediately usable.
 */
internal class EmbeddedAdbPortCandidatePool(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES
) {
    data class Selection(
        val port: Int?,
        val retryAfterMs: Long?
    )

    private data class Candidate(
        var lastSeenElapsed: Long,
        var blockedUntilElapsed: Long = 0L
    )

    private val candidates = linkedMapOf<Int, Candidate>()

    @Synchronized
    fun clear() {
        candidates.clear()
    }

    /** Returns true only when this is a newly observed port. */
    @Synchronized
    fun offer(port: Int, nowElapsed: Long): Boolean {
        require(port in 1..65535) { "invalid ADB port: $port" }
        val existing = candidates[port]
        if (existing != null) {
            existing.lastSeenElapsed = nowElapsed
            return false
        }
        candidates[port] = Candidate(lastSeenElapsed = nowElapsed)
        trimToLimit()
        return true
    }

    @Synchronized
    fun next(nowElapsed: Long): Selection {
        val eligible = candidates.entries
            .asSequence()
            .filter { it.value.blockedUntilElapsed <= nowElapsed }
            .minByOrNull { it.value.lastSeenElapsed }
        if (eligible != null) return Selection(eligible.key, null)

        val nextRetry = candidates.values
            .map { it.blockedUntilElapsed - nowElapsed }
            .filter { it > 0L }
            .minOrNull()
        return Selection(port = null, retryAfterMs = nextRetry)
    }

    @Synchronized
    fun markEndpointFailure(port: Int, nowElapsed: Long): Long {
        val candidate = candidates[port] ?: Candidate(nowElapsed).also { candidates[port] = it }
        candidate.blockedUntilElapsed = maxOf(candidate.blockedUntilElapsed, nowElapsed + cooldownMs)
        return candidate.blockedUntilElapsed - nowElapsed
    }

    @Synchronized
    fun remove(port: Int) {
        candidates.remove(port)
    }

    @Synchronized
    fun ports(): List<Int> = candidates.keys.toList()

    @Synchronized
    fun isEmpty(): Boolean = candidates.isEmpty()

    private fun trimToLimit() {
        while (candidates.size > maxCandidates) {
            val oldest = candidates.minByOrNull { it.value.lastSeenElapsed }?.key ?: return
            candidates.remove(oldest)
        }
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 45_000L
        private const val DEFAULT_MAX_CANDIDATES = 8
    }
}
