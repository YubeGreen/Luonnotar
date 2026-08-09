package com.yubegreen.luonnotar.privileged

/**
 * Shared, side-effect-free recovery state machine for remote control-plane channels.
 *
 * The shell guardian owns observation and dispatch. This policy only converts
 * monotonic timestamps plus health into one of five explicit phases so ADB :5555
 * and Termux sshd use the same grace/cooldown semantics.
 */
internal object ControlPlaneRecoveryPolicy {
    enum class Phase(val wireName: String) {
        DISABLED("disabled"),
        HEALTHY("healthy"),
        MISSING_GRACE("missing_grace"),
        RECOVERY_DUE("recovery_due"),
        BACKOFF("backoff")
    }

    fun shouldProbe(
        nowElapsed: Long,
        lastProbeElapsed: Long,
        intervalMs: Long
    ): Boolean {
        if (nowElapsed < 0L || intervalMs <= 0L) return false
        if (lastProbeElapsed <= 0L || lastProbeElapsed > nowElapsed) return true
        return nowElapsed - lastProbeElapsed >= intervalMs
    }

    fun phase(
        nowElapsed: Long,
        enabled: Boolean,
        healthy: Boolean,
        missingSinceElapsed: Long,
        lastRecoveryElapsed: Long,
        graceMs: Long,
        cooldownMs: Long
    ): Phase {
        if (!enabled) return Phase.DISABLED
        if (healthy) return Phase.HEALTHY
        if (nowElapsed < 0L || graceMs < 0L || cooldownMs < 0L) {
            return Phase.MISSING_GRACE
        }
        if (missingSinceElapsed <= 0L || missingSinceElapsed > nowElapsed) {
            return Phase.MISSING_GRACE
        }
        if (nowElapsed < saturatingAdd(missingSinceElapsed, graceMs)) {
            return Phase.MISSING_GRACE
        }
        if (lastRecoveryElapsed <= 0L || lastRecoveryElapsed > nowElapsed) {
            return Phase.RECOVERY_DUE
        }
        return if (nowElapsed < saturatingAdd(lastRecoveryElapsed, cooldownMs)) {
            Phase.BACKOFF
        } else {
            Phase.RECOVERY_DUE
        }
    }

    fun shouldRecover(
        nowElapsed: Long,
        enabled: Boolean,
        healthy: Boolean,
        missingSinceElapsed: Long,
        lastRecoveryElapsed: Long,
        graceMs: Long,
        cooldownMs: Long
    ): Boolean = phase(
        nowElapsed = nowElapsed,
        enabled = enabled,
        healthy = healthy,
        missingSinceElapsed = missingSinceElapsed,
        lastRecoveryElapsed = lastRecoveryElapsed,
        graceMs = graceMs,
        cooldownMs = cooldownMs
    ) == Phase.RECOVERY_DUE

    /** -1 means no pending recovery deadline while disabled/healthy. */
    fun nextRecoveryEligibleElapsed(
        nowElapsed: Long,
        enabled: Boolean,
        healthy: Boolean,
        missingSinceElapsed: Long,
        lastRecoveryElapsed: Long,
        graceMs: Long,
        cooldownMs: Long
    ): Long {
        return when (
            phase(
                nowElapsed = nowElapsed,
                enabled = enabled,
                healthy = healthy,
                missingSinceElapsed = missingSinceElapsed,
                lastRecoveryElapsed = lastRecoveryElapsed,
                graceMs = graceMs,
                cooldownMs = cooldownMs
            )
        ) {
            Phase.DISABLED, Phase.HEALTHY -> -1L
            Phase.MISSING_GRACE -> if (missingSinceElapsed > 0L) {
                saturatingAdd(missingSinceElapsed, graceMs)
            } else {
                -1L
            }
            Phase.RECOVERY_DUE -> nowElapsed.coerceAtLeast(0L)
            Phase.BACKOFF -> saturatingAdd(lastRecoveryElapsed, cooldownMs)
        }
    }

    fun listeningOnPort(output: String, port: Int): Boolean {
        if (port !in 1..65535) return false
        return output.lineSequence().any { line ->
            val fields = line.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            if (!fields.firstOrNull().equals("LISTEN", ignoreCase = true)) return@any false
            val localEndpoint = fields.getOrNull(3) ?: return@any false
            endpointPort(localEndpoint) == port
        }
    }

    private fun endpointPort(endpoint: String): Int? {
        val normalized = endpoint.trim().removePrefix("[").replace("]:", ":")
        return normalized.substringAfterLast(':', missingDelimiterValue = "").toIntOrNull()
    }

    private fun saturatingAdd(value: Long, delta: Long): Long {
        if (delta <= 0L) return value
        return if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
    }
}
