package com.yubegreen.luonnotar.privileged

object GuardianActionPolicy {
    fun shouldReassert(
        previousPid: Int?,
        currentPid: Int,
        lastActionElapsed: Long?,
        nowElapsed: Long,
        reassertIntervalMs: Long
    ): Boolean {
        if (previousPid == null || previousPid != currentPid) return true
        if (lastActionElapsed == null || lastActionElapsed <= 0L) return true
        if (nowElapsed < lastActionElapsed) return true
        return nowElapsed - lastActionElapsed >= reassertIntervalMs.coerceAtLeast(1L)
    }
}
