package com.yubegreen.luonnotar.privileged

internal object NotificationListenerShellGuardianPolicy {
    enum class Action { NONE, ORDINARY_REBIND, STRONG_REREGISTER }

    data class Decision(val action: Action, val reason: String)

    fun decide(
        privacyAcknowledged: Boolean,
        systemAuthorized: Boolean,
        healthy: Boolean,
        nowElapsed: Long,
        unhealthySinceElapsed: Long,
        lastOrdinaryRebindElapsed: Long,
        lastStrongRecoveryElapsed: Long,
        strongAfterMs: Long,
        strongCooldownMs: Long
    ): Decision {
        if (!privacyAcknowledged) return Decision(Action.NONE, "privacy_not_acknowledged")
        if (!systemAuthorized) return Decision(Action.NONE, "system_access_not_authorized")
        if (healthy) return Decision(Action.NONE, "healthy")
        if (nowElapsed < 0L) return Decision(Action.NONE, "invalid_clock")
        if (unhealthySinceElapsed <= 0L) {
            return Decision(Action.ORDINARY_REBIND, "new_unhealthy_episode")
        }
        if (lastOrdinaryRebindElapsed <= 0L) {
            return Decision(Action.ORDINARY_REBIND, "ordinary_rebind_not_attempted")
        }
        val unhealthyFor = (nowElapsed - unhealthySinceElapsed).coerceAtLeast(0L)
        if (unhealthyFor < strongAfterMs) {
            return Decision(Action.NONE, "ordinary_rebind_grace")
        }
        val cooldownReady =
            lastStrongRecoveryElapsed <= 0L ||
                nowElapsed - lastStrongRecoveryElapsed >= strongCooldownMs
        if (!cooldownReady) {
            return Decision(Action.NONE, "strong_recovery_cooldown")
        }
        return Decision(Action.STRONG_REREGISTER, "ordinary_rebind_stalled")
    }
}
