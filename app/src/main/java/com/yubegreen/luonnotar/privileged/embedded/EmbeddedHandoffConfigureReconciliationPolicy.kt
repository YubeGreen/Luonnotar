package com.yubegreen.luonnotar.privileged.embedded

import org.json.JSONObject
import java.net.SocketTimeoutException

internal object EmbeddedHandoffConfigureReconciliationPolicy {
    fun shouldAttemptLateReconcile(error: Throwable?): Boolean =
        error is SocketTimeoutException

    fun isFullyConfiguredStatus(rawStatus: String): Boolean =
        !JSONObject(rawStatus).optBoolean("handoffActivation", false)
}
