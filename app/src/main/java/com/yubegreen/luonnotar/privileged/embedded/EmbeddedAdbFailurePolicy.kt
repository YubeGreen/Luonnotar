package com.yubegreen.luonnotar.privileged.embedded

/** Classifies local ADB endpoint failures separately from host-key authorization failures. */
internal object EmbeddedAdbFailurePolicy {
    fun isEndpointUnavailable(error: Throwable): Boolean =
        generateSequence(error as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .any(::isEndpointUnavailable)

    fun isEndpointUnavailable(message: String): Boolean {
        val normalized = message.lowercase()
        return ENDPOINT_MARKERS.any(normalized::contains)
    }

    fun isAuthorizationFailure(error: Throwable): Boolean =
        generateSequence(error as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .any(::isAuthorizationFailure)

    fun isAuthorizationFailure(message: String): Boolean {
        val normalized = message.lowercase()
        return AUTH_MARKERS.any(normalized::contains)
    }

    private val ENDPOINT_MARKERS = listOf(
        "econnrefused",
        "connection refused",
        "failed to connect",
        "connect timed out",
        "connection timed out",
        "no route to host",
        "ehostunreach",
        "connection reset",
        "socket closed",
        "broken pipe"
    )

    private val AUTH_MARKERS = listOf(
        "unauthorized",
        "not authorized",
        "authentication failed",
        "failed to authenticate",
        "auth failed",
        "public key rejected",
        "certificate rejected",
        "tls alert",
        "handshake_failure",
        "handshake failure"
    )
}
