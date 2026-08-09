package com.yubegreen.luonnotar.privileged

/**
 * Health/recovery cadence for the fixed ADB TCP listener used by remote devices.
 *
 * The shell guardian decides when recovery is warranted. v128 resolves the live
 * Wireless ADB port through IAdbManager before dispatching the app-side Kadb transport, so
 * this policy remains side-effect free and independently testable.
 */
internal object AdbTcpPortHealthPolicy {
    const val PORT = 5555
    // Engine cycles every ~15s. Two consecutive missing probes are enough to
    // recover, while a one-minute cooldown prevents restart storms.
    const val PROBE_INTERVAL_MS = 15_000L
    const val MISSING_GRACE_MS = 15_000L
    const val RECOVERY_COOLDOWN_MS = 60_000L

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
