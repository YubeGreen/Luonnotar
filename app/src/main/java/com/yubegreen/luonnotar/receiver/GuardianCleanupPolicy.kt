package com.yubegreen.luonnotar.receiver

object GuardianCleanupPolicy {
    fun shouldCancelForDisabled(enabled: Boolean): Boolean = !enabled

    fun shouldCancelForPaused(enabled: Boolean, paused: Boolean): Boolean =
        !enabled || paused
}
