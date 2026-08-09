package com.yubegreen.luonnotar.privileged

/** Passive process/listener health + bounded recovery cadence for Termux sshd. */
internal object TermuxSshdHealthPolicy {
    const val PROBE_INTERVAL_MS = 15_000L
    const val MISSING_GRACE_MS = 15_000L
    const val RECOVERY_COOLDOWN_MS = 60_000L

    fun shouldProbe(nowElapsed: Long, lastProbeElapsed: Long): Boolean =
        ControlPlaneRecoveryPolicy.shouldProbe(
            nowElapsed = nowElapsed,
            lastProbeElapsed = lastProbeElapsed,
            intervalMs = PROBE_INTERVAL_MS
        )

    fun phase(
        nowElapsed: Long,
        enabled: Boolean,
        healthy: Boolean,
        missingSinceElapsed: Long,
        lastRecoveryElapsed: Long
    ): ControlPlaneRecoveryPolicy.Phase = ControlPlaneRecoveryPolicy.phase(
        nowElapsed = nowElapsed,
        enabled = enabled,
        healthy = healthy,
        missingSinceElapsed = missingSinceElapsed,
        lastRecoveryElapsed = lastRecoveryElapsed,
        graceMs = MISSING_GRACE_MS,
        cooldownMs = RECOVERY_COOLDOWN_MS
    )

    fun nextRecoveryEligibleElapsed(
        nowElapsed: Long,
        enabled: Boolean,
        healthy: Boolean,
        missingSinceElapsed: Long,
        lastRecoveryElapsed: Long
    ): Long = ControlPlaneRecoveryPolicy.nextRecoveryEligibleElapsed(
        nowElapsed = nowElapsed,
        enabled = enabled,
        healthy = healthy,
        missingSinceElapsed = missingSinceElapsed,
        lastRecoveryElapsed = lastRecoveryElapsed,
        graceMs = MISSING_GRACE_MS,
        cooldownMs = RECOVERY_COOLDOWN_MS
    )

    fun shouldRecover(
        nowElapsed: Long,
        armed: Boolean,
        missingSinceElapsed: Long,
        lastRecoveryElapsed: Long
    ): Boolean = ControlPlaneRecoveryPolicy.shouldRecover(
        nowElapsed = nowElapsed,
        enabled = armed,
        healthy = false,
        missingSinceElapsed = missingSinceElapsed,
        lastRecoveryElapsed = lastRecoveryElapsed,
        graceMs = MISSING_GRACE_MS,
        cooldownMs = RECOVERY_COOLDOWN_MS
    )

    fun processRunning(pidofOutput: String): Boolean = pidofOutput
        .trim()
        .split(Regex("\\s+"))
        .any { it.toIntOrNull()?.let { pid -> pid > 0 } == true }

    fun listeningOnPort(output: String, port: Int): Boolean =
        ControlPlaneRecoveryPolicy.listeningOnPort(output, port)
}
