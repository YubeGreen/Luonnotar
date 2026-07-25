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
