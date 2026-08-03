package com.yubegreen.luonnotar.service

enum class ProbeWatchdogAction {
    NONE,
    REBUILD_EXECUTOR,
    RESTART_KEEPER_PROCESS
}

object ProbeWatchdogPolicy {
    fun action(
        logicalInFlight: Boolean,
        actualInFlight: Boolean,
        ageMs: Long,
        softTimeoutMs: Long,
        hardTimeoutMs: Long
    ): ProbeWatchdogAction = when {
        actualInFlight && !logicalInFlight && ageMs >= hardTimeoutMs ->
            ProbeWatchdogAction.RESTART_KEEPER_PROCESS
        logicalInFlight && ageMs >= softTimeoutMs ->
            ProbeWatchdogAction.REBUILD_EXECUTOR
        else -> ProbeWatchdogAction.NONE
    }
}

object ProbeHardRestartPolicy {
    fun leaseStillEligible(
        expected: ActualProbePermitSnapshot,
        current: ActualProbePermitSnapshot,
        nowElapsed: Long,
        hardTimeoutMs: Long,
        expectedVpnHandle: Long,
        currentVpnHandle: Long
    ): Boolean =
        expected.owner != null &&
            expected.stage == "HTTPS" &&
            current.stage == "HTTPS" &&
            current.owner === expected.owner &&
            current.owner.generation == expected.owner.generation &&
            current.acquiredElapsed == expected.acquiredElapsed &&
            current.networkHandle == expected.networkHandle &&
            current.acquiredElapsed > 0L &&
            nowElapsed - current.acquiredElapsed >= hardTimeoutMs &&
            currentVpnHandle == expectedVpnHandle
}
