package com.yubegreen.luonnotar.privileged

/** Evidence emitted by GMS itself that helps distinguish a transient socket
 * gap from an authentication/transport failure. */
enum class GmsTransportLogSignalKind {
    BAD_AUTHENTICATION,
    MCS_CONNECT_ATTEMPT,
    NETWORK_TRANSITION,
    CONTROLLED_DELIVERY
}

data class GmsTransportLogSignal(
    val kind: GmsTransportLogSignalKind,
    val rawLine: String
)

object GmsTransportLogSignalParser {
    fun parse(line: String): GmsTransportLogSignal? = when {
        line.contains("BAD_AUTHENTICATION", ignoreCase = true) &&
            line.contains("com.google.android.gms", ignoreCase = true) ->
            GmsTransportLogSignal(GmsTransportLogSignalKind.BAD_AUTHENTICATION, line)

        line.contains("Process com.google.android.gms.persistent", ignoreCase = true) &&
            MCS_PORTS.any { port -> line.contains(":$port") } ->
            GmsTransportLogSignal(GmsTransportLogSignalKind.MCS_CONNECT_ATTEMPT, line)

        line.contains("Luonnotar", ignoreCase = true) &&
            NETWORK_EVENTS.any { event -> line.contains(event, ignoreCase = true) } ->
            GmsTransportLogSignal(GmsTransportLogSignalKind.NETWORK_TRANSITION, line)

        line.contains("Luonnotar", ignoreCase = true) &&
            line.contains("push_test_arrival_observed", ignoreCase = true) ->
            GmsTransportLogSignal(GmsTransportLogSignalKind.CONTROLLED_DELIVERY, line)

        else -> null
    }

    private val MCS_PORTS = setOf(443, 5228, 5229, 5230)
    private val NETWORK_EVENTS = setOf(
        "default_network_handle_changed",
        "vpn_network_changed",
        "vpn_recovered"
    )
}

data class GmsTransportProbe(
    val observable: Boolean,
    val establishedPorts: Set<Int>,
    val detail: String
) {
    val healthy: Boolean get() = establishedPorts.isNotEmpty()
}

/** Parses `ss -H -tnp`. Ports 5228-5230 are dedicated FCM/MCS
 * transport ports and remain useful even when a ROM hides socket owners.
 * Port 443 is accepted only when the same line exposes a GMS owner; treating
 * every HTTPS socket as FCM would create dangerous false positives. */
object GmsTransportSocketParser {
    private val DEDICATED_MCS_PORTS = setOf(5228, 5229, 5230)

    fun establishedMcsPorts(raw: String): Set<Int> = buildSet {
        raw.lineSequence().forEach { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 5) return@forEach
            val state = fields.first()
            if (!state.equals("ESTAB", true) && !state.equals("ESTABLISHED", true)) {
                return@forEach
            }
            val peer = fields[4]
            val port = peer.substringAfterLast(':').trimEnd(']').toIntOrNull()
                ?: return@forEach
            when {
                port in DEDICATED_MCS_PORTS -> add(port)
                port == 443 && line.contains("com.google.android.gms", ignoreCase = true) -> add(port)
            }
        }
    }
}

/** Pure decision logic for active GMS transport recovery. */
object GmsTransportHealthPolicy {
    data class Decision(val recover: Boolean, val reason: String)

    fun decide(
        automaticEnabled: Boolean,
        nowElapsed: Long,
        probe: GmsTransportProbe,
        gmsPersistentRunning: Boolean,
        consecutiveMissing: Int,
        missingSinceElapsed: Long,
        lastHealthyElapsed: Long,
        lastBadAuthenticationElapsed: Long,
        lastConnectAttemptElapsed: Long,
        evidenceWindowMs: Long,
        missingAfterBadAuthMs: Long,
        transportLostMs: Long
    ): Decision {
        if (!automaticEnabled) return Decision(false, "automatic_recovery_disabled")
        if (nowElapsed < 0L) return Decision(false, "invalid_clock")
        if (!gmsPersistentRunning) return Decision(false, "gms_persistent_not_running")
        if (!probe.observable) return Decision(false, "transport_unobservable")
        if (probe.healthy) return Decision(false, "transport_healthy")
        if (missingSinceElapsed <= 0L || missingSinceElapsed > nowElapsed) {
            return Decision(false, "missing_window_not_established")
        }

        val missingFor = nowElapsed - missingSinceElapsed
        val badAuthRecent = lastBadAuthenticationElapsed > 0L &&
            lastBadAuthenticationElapsed <= nowElapsed &&
            nowElapsed - lastBadAuthenticationElapsed <= evidenceWindowMs
        val connectAttemptRecent = lastConnectAttemptElapsed > 0L &&
            lastConnectAttemptElapsed <= nowElapsed &&
            nowElapsed - lastConnectAttemptElapsed <= evidenceWindowMs

        if (badAuthRecent && consecutiveMissing >= 3 && missingFor >= missingAfterBadAuthMs) {
            return Decision(true, "mcs_missing_after_bad_auth")
        }
        if (
            connectAttemptRecent &&
            lastHealthyElapsed > 0L &&
            lastHealthyElapsed <= nowElapsed &&
            consecutiveMissing >= 6 &&
            missingFor >= transportLostMs
        ) {
            return Decision(true, "mcs_reconnect_stalled")
        }
        return Decision(false, "insufficient_transport_evidence")
    }
}
