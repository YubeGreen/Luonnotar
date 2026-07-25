package com.yubegreen.luonnotar.receiver

import java.security.MessageDigest

data class BootRecoveryDecision(
    val deduplicated: Boolean,
    val reason: String,
    val deltaElapsed: Long
)

object BootRecoveryDedupPolicy {
    const val DEDUP_WINDOW_MS = 25_000L

    private val clusteredBootActions = setOf(
        "android.intent.action.LOCKED_BOOT_COMPLETED",
        "android.intent.action.BOOT_COMPLETED",
        "android.intent.action.USER_UNLOCKED"
    )

    fun isClusteredBootAction(action: String): Boolean =
        action in clusteredBootActions

    fun requiresUnlockedMaintenance(action: String): Boolean =
        action != "android.intent.action.LOCKED_BOOT_COMPLETED"

    fun decide(
        action: String,
        currentBootId: String,
        nowElapsed: Long,
        lastBootId: String?,
        lastAcceptedElapsed: Long,
        lastDispatchAccepted: Boolean
    ): BootRecoveryDecision {
        if (action !in clusteredBootActions) {
            return BootRecoveryDecision(false, "independent_action", -1L)
        }
        if (currentBootId.isBlank()) {
            return BootRecoveryDecision(false, "boot_id_unavailable", -1L)
        }
        if (!lastDispatchAccepted) {
            return BootRecoveryDecision(false, "no_accepted_prior_dispatch", -1L)
        }
        if (lastBootId != currentBootId) {
            return BootRecoveryDecision(false, "different_boot", -1L)
        }
        val delta = nowElapsed - lastAcceptedElapsed
        if (lastAcceptedElapsed <= 0L || delta < 0L) {
            return BootRecoveryDecision(false, "elapsed_realtime_reset", delta)
        }
        return if (delta < DEDUP_WINDOW_MS) {
            BootRecoveryDecision(true, "same_boot_within_window", delta)
        } else {
            BootRecoveryDecision(false, "dedup_window_expired", delta)
        }
    }

    fun anonymousBootId(bootId: String): String {
        if (bootId.isBlank()) return "unavailable"
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(bootId.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}
