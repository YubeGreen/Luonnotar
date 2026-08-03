package com.yubegreen.luonnotar.privileged.embedded

/**
 * Safety gate for the legacy localhost ADB fallback.
 *
 * Port 5555 is considered only when the live service property says exactly
 * 5555, the embedded ADB identity has already been paired, and a short TCP
 * probe confirms a listener on localhost. A persisted property by itself is
 * diagnostic only because it can be stale across adbd restarts.
 */
internal object LocalAdbTcpFallbackPolicy {
    const val PORT = 5555

    data class Decision(
        val allowed: Boolean,
        val port: Int? = null,
        val reason: String
    )

    fun decide(
        paired: Boolean,
        serviceTcpPort: String,
        persistedTcpPort: String,
        socketReachable: Boolean
    ): Decision {
        if (!paired) return Decision(false, reason = "identity_not_paired")
        if (serviceTcpPort.trim() != PORT.toString()) {
            val reason = if (persistedTcpPort.trim() == PORT.toString()) {
                "persist_only_stale_risk"
            } else {
                "service_property_not_5555"
            }
            return Decision(false, reason = reason)
        }
        if (!socketReachable) return Decision(false, reason = "localhost_5555_unreachable")
        return Decision(true, port = PORT, reason = "verified_live_localhost_5555")
    }
}
