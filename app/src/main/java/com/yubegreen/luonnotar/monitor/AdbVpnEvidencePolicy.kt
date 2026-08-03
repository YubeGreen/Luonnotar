package com.yubegreen.luonnotar.monitor

object AdbVpnEvidencePolicy {
    const val STALE_AFTER_MS = 60 * 60_000L

    enum class Freshness {
        CURRENT,
        STALE,
        INVALID
    }

    fun freshness(
        verifiedElapsed: Long,
        nowElapsed: Long,
        verifiedBootId: String,
        currentBootId: String,
        activePackage: String,
        evidenceHash: String,
        verifiedNetworkHandle: Long,
        currentNetworkHandle: Long,
        vpnPresent: Boolean,
        verifiedSessionFingerprint: String,
        currentSessionFingerprint: String,
        currentProviderPackage: String? = null
    ): Freshness {
        val age = nowElapsed - verifiedElapsed
        val validBinding =
            verifiedElapsed > 0L &&
                age >= 0L &&
                verifiedBootId.isNotBlank() &&
                verifiedBootId == currentBootId &&
                activePackage.isNotBlank() &&
                (
                    currentProviderPackage.isNullOrBlank() ||
                        currentProviderPackage == activePackage
                    ) &&
                evidenceHash.isNotBlank() &&
                verifiedNetworkHandle >= 0L &&
                verifiedNetworkHandle == currentNetworkHandle &&
                verifiedSessionFingerprint.isNotBlank() &&
                verifiedSessionFingerprint == currentSessionFingerprint &&
                vpnPresent
        return when {
            !validBinding -> Freshness.INVALID
            age > STALE_AFTER_MS -> Freshness.STALE
            else -> Freshness.CURRENT
        }
    }

    fun isCurrent(
        verifiedElapsed: Long,
        nowElapsed: Long,
        verifiedBootId: String,
        currentBootId: String,
        activePackage: String,
        evidenceHash: String,
        verifiedNetworkHandle: Long,
        currentNetworkHandle: Long,
        vpnPresent: Boolean,
        verifiedSessionFingerprint: String,
        currentSessionFingerprint: String,
        currentProviderPackage: String? = null
    ): Boolean = freshness(
        verifiedElapsed = verifiedElapsed,
        nowElapsed = nowElapsed,
        verifiedBootId = verifiedBootId,
        currentBootId = currentBootId,
        activePackage = activePackage,
        evidenceHash = evidenceHash,
        verifiedNetworkHandle = verifiedNetworkHandle,
        currentNetworkHandle = currentNetworkHandle,
        vpnPresent = vpnPresent,
        verifiedSessionFingerprint = verifiedSessionFingerprint,
        currentSessionFingerprint = currentSessionFingerprint,
        currentProviderPackage = currentProviderPackage
    ) == Freshness.CURRENT
}
