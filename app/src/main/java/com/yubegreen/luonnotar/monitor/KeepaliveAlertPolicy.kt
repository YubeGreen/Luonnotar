package com.yubegreen.luonnotar.monitor

object KeepaliveAlertPolicy {
    fun shouldAlertHttps(
        paused: Boolean,
        vpnPresent: Boolean,
        validated: Boolean,
        lastAttemptElapsed: Long,
        attemptEvidenceIsCurrent: Boolean,
        failures: Int,
        hasAnySuccess: Boolean,
        hasRecentSuccess: Boolean
    ): Boolean =
        !paused &&
            vpnPresent &&
            validated &&
            lastAttemptElapsed > 0L &&
            attemptEvidenceIsCurrent &&
            (failures >= 2 || (hasAnySuccess && !hasRecentSuccess))
}
