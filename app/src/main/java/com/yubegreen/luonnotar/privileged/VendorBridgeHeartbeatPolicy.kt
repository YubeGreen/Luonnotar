package com.yubegreen.luonnotar.privileged

/**
 * r297 bridge liveness policy.
 *
 * The bridge writes two independent heartbeats:
 * - protocol heartbeat records flow through stdout and prove the main bridge
 *   loop is still making progress;
 * - the heartbeat file is refreshed by a background shell and proves only that
 *   the shell process/identity is still alive.
 *
 * A fresh file must never mask a stale protocol heartbeat.
 */
internal object VendorBridgeHeartbeatPolicy {
    enum class Action {
        NONE,
        START,
        RESTART_PROTOCOL_STALLED,
        RESTART_FILE_STALE
    }

    data class Decision(
        val action: Action,
        val reason: String
    )

    fun decide(
        alive: Boolean,
        ready: Boolean,
        protocolAgeMs: Long,
        fileValid: Boolean,
        fileAgeMs: Long,
        staleMs: Long
    ): Decision {
        if (!alive) return Decision(Action.START, "process_not_alive")
        if (!ready) return Decision(Action.NONE, "awaiting_ready")

        if (protocolAgeMs > staleMs) {
            return Decision(
                Action.RESTART_PROTOCOL_STALLED,
                if (fileValid && fileAgeMs in 0..staleMs) {
                    "protocol_stalled_file_fresh"
                } else {
                    "protocol_stalled"
                }
            )
        }
        if (!fileValid || fileAgeMs < 0L || fileAgeMs > staleMs) {
            return Decision(Action.RESTART_FILE_STALE, "heartbeat_file_stale")
        }
        return Decision(Action.NONE, "healthy")
    }
}
