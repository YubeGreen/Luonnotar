package com.yubegreen.luonnotar.privileged.embedded

/**
 * A slow or failed privileged operation is not proof that the embedded engine died.
 * Only a separate, short authenticated ping may revoke a previously verified connection.
 */
internal object EmbeddedConnectionFailurePolicy {
    fun shouldMarkDead(shortPingSucceeded: Boolean): Boolean = !shortPingSucceeded
}
