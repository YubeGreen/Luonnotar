package com.yubegreen.luonnotar.privileged

/**
 * Read-only health policy for the local ADB TCP listener used by remote devices.
 *
 * The guardian never restarts adbd or changes a TCP port. When a previously
 * observed/configured listener disappears for long enough, it may wake Termux
 * so the user's existing recovery script can reassert the tunnel/listener.
 */
internal object AdbTcpPortHealthPolicy {
    const val PORT = 5555
    const val PROBE_INTERVAL_MS = 60_000L
    const val MISSING_GRACE_MS = 90_000L
    const val RECOVERY_COOLDOWN_MS = 5 * 60_000L
    const val FOREGROUND_HOLD_MS = 8_000L

    fun listeningOnPort(output: String, port: Int = PORT): Boolean =
        output.lineSequence().any { line ->
            val fields = line.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            if (!fields.firstOrNull().equals("LISTEN", ignoreCase = true)) return@any false
            val localEndpoint = fields.getOrNull(3) ?: return@any false
            endpointPort(localEndpoint) == port
        }

    fun shouldProbe(nowElapsed: Long, lastProbeElapsed: Long): Boolean {
        if (nowElapsed < 0L || lastProbeElapsed > nowElapsed) return false
        if (lastProbeElapsed <= 0L) return true
        return nowElapsed - lastProbeElapsed >= PROBE_INTERVAL_MS
    }

    fun shouldRecover(
        nowElapsed: Long,
        armed: Boolean,
        missingSinceElapsed: Long,
        lastRecoveryElapsed: Long
    ): Boolean {
        if (
            !armed ||
            nowElapsed < 0L ||
            missingSinceElapsed <= 0L ||
            missingSinceElapsed > nowElapsed ||
            lastRecoveryElapsed > nowElapsed
        ) {
            return false
        }
        if (nowElapsed - missingSinceElapsed < MISSING_GRACE_MS) return false
        if (lastRecoveryElapsed <= 0L) return true
        return nowElapsed - lastRecoveryElapsed >= RECOVERY_COOLDOWN_MS
    }

    private fun endpointPort(endpoint: String): Int? {
        val normalized = endpoint.trim().removePrefix("[").replace("]:", ":")
        return normalized.substringAfterLast(':', missingDelimiterValue = "").toIntOrNull()
    }
}
