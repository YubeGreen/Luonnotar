package com.yubegreen.luonnotar.monitor

object AdbVpnEvidencePolicy {
    const val MAX_AGE_MS = 5 * 60_000L

    fun isCurrent(
        verifiedElapsed: Long,
        nowElapsed: Long,
        verifiedBootId: String,
        currentBootId: String,
        activePackage: String,
        evidenceHash: String,
        verifiedNetworkHandle: Long,
        currentNetworkHandle: Long,
        vpnPresent: Boolean
    ): Boolean {
        val age = nowElapsed - verifiedElapsed
        return verifiedElapsed > 0L &&
            age in 0L..MAX_AGE_MS &&
            verifiedBootId.isNotBlank() &&
            verifiedBootId == currentBootId &&
            activePackage.isNotBlank() &&
            evidenceHash.isNotBlank() &&
            verifiedNetworkHandle >= 0L &&
            verifiedNetworkHandle == currentNetworkHandle &&
            vpnPresent
    }
}
