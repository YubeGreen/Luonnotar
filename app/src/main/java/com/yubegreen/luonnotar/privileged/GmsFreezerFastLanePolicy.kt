package com.yubegreen.luonnotar.privileged

/** Pure terminal-state policy shared by the shell fast-lane protocol handlers. */
internal object GmsFreezerFastLanePolicy {
    fun isRecoveryReady(state: String, acceptedCount: Int): Boolean =
        state == "thawed" || (state == "unobservable" && acceptedCount > 0)

    fun requiresKotlinFallback(
        state: String,
        acceptedCount: Int,
        exhausted: Boolean
    ): Boolean {
        if (state == "thawed") return false
        if (exhausted) return true
        return !isRecoveryReady(state, acceptedCount)
    }
}
