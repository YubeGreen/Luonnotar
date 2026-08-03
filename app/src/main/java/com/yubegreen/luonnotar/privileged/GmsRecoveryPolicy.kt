package com.yubegreen.luonnotar.privileged

/** Pure decision logic for the destructive GMS recovery tier. */
object GmsRecoveryPolicy {
    data class Decision(val allowed: Boolean, val reason: String)

    fun decide(
        automaticEnabled: Boolean,
        manual: Boolean,
        nowElapsed: Long,
        freezeEvents: List<Long>,
        lastRecoveryElapsed: Long,
        recoveryHistory: List<Long>,
        freezeThreshold: Int,
        freezeWindowMs: Long,
        automaticCooldownMs: Long,
        manualCooldownMs: Long,
        maxRecoveriesPer24Hours: Int,
        automaticEvidenceReason: String? = null
    ): Decision {
        if (nowElapsed < 0L) return Decision(false, "invalid_clock")
        if (!manual && !automaticEnabled) return Decision(false, "automatic_recovery_disabled")

        val cooldown = if (manual) manualCooldownMs else automaticCooldownMs
        if (
            lastRecoveryElapsed > 0L &&
            nowElapsed >= lastRecoveryElapsed &&
            nowElapsed - lastRecoveryElapsed < cooldown
        ) {
            return Decision(false, "cooldown")
        }
        if (lastRecoveryElapsed > nowElapsed) return Decision(false, "elapsed_clock_reset")

        val dayStart = (nowElapsed - DAY_MS).coerceAtLeast(0L)
        val recentRecoveries = recoveryHistory.count { it in dayStart..nowElapsed }
        if (recentRecoveries >= maxRecoveriesPer24Hours.coerceAtLeast(1)) {
            return Decision(false, "daily_limit")
        }

        if (manual) return Decision(true, "manual")
        automaticEvidenceReason?.takeIf(String::isNotBlank)?.let { reason ->
            return Decision(true, reason)
        }

        val windowStart = (nowElapsed - freezeWindowMs.coerceAtLeast(1L)).coerceAtLeast(0L)
        val recentFreezes = freezeEvents.count { it in windowStart..nowElapsed }
        if (recentFreezes < freezeThreshold.coerceAtLeast(1)) {
            return Decision(false, "insufficient_freeze_evidence")
        }
        return Decision(true, "repeated_gms_freeze")
    }

    private const val DAY_MS = 24L * 60L * 60L * 1_000L
}
