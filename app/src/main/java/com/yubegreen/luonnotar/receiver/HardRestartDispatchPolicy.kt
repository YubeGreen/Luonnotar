package com.yubegreen.luonnotar.receiver

enum class HardRestartDispatchAction {
    REJECT,
    RESCHEDULE_OLD_PID,
    START_NEW_KEEPER
}

object HardRestartDispatchPolicy {
    fun decide(
        metadataMatches: Boolean,
        currentPid: Int,
        expectedOldPid: Int
    ): HardRestartDispatchAction = when {
        !metadataMatches -> HardRestartDispatchAction.REJECT
        currentPid == expectedOldPid ->
            HardRestartDispatchAction.RESCHEDULE_OLD_PID
        else -> HardRestartDispatchAction.START_NEW_KEEPER
    }
}

object HardRestartRetryPolicy {
    fun maySchedule(
        attemptCount: Int,
        elapsedSinceFirstMs: Long,
        maxAttempts: Int,
        maxWindowMs: Long
    ): Boolean =
        attemptCount in 1..maxAttempts &&
            elapsedSinceFirstMs in 0..maxWindowMs
}
