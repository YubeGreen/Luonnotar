package com.yubegreen.luonnotar.service

object GuardianLiveness {
    const val DASHBOARD_STALE_MS = 75_000L
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
        nowUptime: Long = 0L,
        lastTickUptime: Long = 0L,
        serviceStartedElapsed: Long = 0L,
        thresholdMs: Long = RECOVERY_STALE_MS
    ): Boolean {
        if (!enabled) return false
        if (servicePid <= 0 || keeperProcessPid <= 0) return true
        if (servicePid != keeperProcessPid) return true
        val inStartupGrace =
            serviceStartedElapsed > 0L &&
                serviceStartedElapsed <= nowElapsed &&
                nowElapsed - serviceStartedElapsed <= STARTUP_GRACE_MS &&
                servicePid > 0 &&
                servicePid == keeperProcessPid
        if (inStartupGrace) return false
        val validUptime = lastTickUptime > 0L && nowUptime >= lastTickUptime
        return if (validUptime) {
            nowUptime - lastTickUptime > thresholdMs
        } else {
            isStale(nowElapsed, heartbeatElapsed, thresholdMs)
        }
    }
}
