package com.yubegreen.luonnotar.service

object GuardianLiveness {
    const val DASHBOARD_STALE_MS = 15_000L
    const val RECOVERY_STALE_MS = 90_000L
    const val STARTUP_GRACE_MS = 10_000L

    fun heartbeatAge(nowElapsed: Long, heartbeatElapsed: Long): Long =
        if (heartbeatElapsed <= 0L || heartbeatElapsed > nowElapsed) {
            Long.MAX_VALUE
        } else {
            nowElapsed - heartbeatElapsed
        }

    fun isStale(
        nowElapsed: Long,
        heartbeatElapsed: Long,
        thresholdMs: Long = RECOVERY_STALE_MS
    ): Boolean = heartbeatAge(nowElapsed, heartbeatElapsed) > thresholdMs

    fun shouldRecover(
        enabled: Boolean,
        nowElapsed: Long,
        heartbeatElapsed: Long,
        servicePid: Int,
        keeperProcessPid: Int,
        serviceStartedElapsed: Long = 0L,
        thresholdMs: Long = RECOVERY_STALE_MS
    ): Boolean {
        val inStartupGrace =
            serviceStartedElapsed > 0L &&
                serviceStartedElapsed <= nowElapsed &&
                nowElapsed - serviceStartedElapsed <= STARTUP_GRACE_MS &&
                servicePid > 0 &&
                servicePid == keeperProcessPid
        return enabled && !inStartupGrace && (
        servicePid <= 0 ||
            keeperProcessPid <= 0 ||
            servicePid != keeperProcessPid ||
            isStale(nowElapsed, heartbeatElapsed, thresholdMs)
        )
    }
}
