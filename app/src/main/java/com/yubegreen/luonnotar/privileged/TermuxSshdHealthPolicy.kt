package com.yubegreen.luonnotar.privileged

/** Passive process health + bounded recovery cadence for Termux sshd. */
internal object TermuxSshdHealthPolicy {
    const val PROBE_INTERVAL_MS = 60_000L
    const val MISSING_GRACE_MS = 90_000L
    const val RECOVERY_COOLDOWN_MS = 5L * 60L * 1_000L

    fun shouldProbe(nowElapsed: Long, lastProbeElapsed: Long): Boolean =
        lastProbeElapsed <= 0L ||
            nowElapsed < lastProbeElapsed ||
            nowElapsed - lastProbeElapsed >= PROBE_INTERVAL_MS

    fun shouldRecover(
        nowElapsed: Long,
        armed: Boolean,
        missingSinceElapsed: Long,
        lastRecoveryElapsed: Long
    ): Boolean {
        if (!armed || missingSinceElapsed <= 0L) return false
        if (nowElapsed < missingSinceElapsed) return false
        if (nowElapsed - missingSinceElapsed < MISSING_GRACE_MS) return false
        return lastRecoveryElapsed <= 0L ||
            nowElapsed < lastRecoveryElapsed ||
            nowElapsed - lastRecoveryElapsed >= RECOVERY_COOLDOWN_MS
    }

    fun processRunning(pidofOutput: String): Boolean = pidofOutput
        .trim()
        .split(Regex("\\s+"))
        .any { it.toIntOrNull()?.let { pid -> pid > 0 } == true }
}
