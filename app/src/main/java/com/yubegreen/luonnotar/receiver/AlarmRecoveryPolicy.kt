package com.yubegreen.luonnotar.receiver

object AlarmRecoveryPolicy {
    enum class Action {
        NONE,
        START_FOREGROUND_SERVICE,
        REQUIRE_USER_INTERACTION
    }

    fun decide(
        enabled: Boolean,
        paused: Boolean,
        heartbeatStale: Boolean,
        sdkInt: Int,
        exactAlarmEligible: Boolean
    ): Action = when {
        !enabled || paused || !heartbeatStale -> Action.NONE
        sdkInt < 31 || exactAlarmEligible -> Action.START_FOREGROUND_SERVICE
        else -> Action.REQUIRE_USER_INTERACTION
    }
}
