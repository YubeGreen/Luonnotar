package com.yubegreen.luonnotar.privileged.embedded

internal object EmbeddedRebootReminderPolicy {
    enum class Action {
        POST,
        CANCEL,
        NONE
    }

    fun decide(
        featureEnabled: Boolean,
        liveConnected: Boolean,
        pending: Boolean
    ): Action = when {
        !featureEnabled -> Action.CANCEL
        liveConnected -> Action.CANCEL
        pending -> Action.POST
        else -> Action.NONE
    }
}
