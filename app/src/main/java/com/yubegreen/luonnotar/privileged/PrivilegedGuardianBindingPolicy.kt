package com.yubegreen.luonnotar.privileged

/** Pure policy for deciding whether an in-flight Shizuku UserService bind is still credible. */
object PrivilegedGuardianBindingPolicy {
    const val BIND_TIMEOUT_MS = 15_000L

    fun canReuseInFlightBind(
        bindingFlag: Boolean,
        remoteConnected: Boolean,
        connectionState: String,
        stateAgeMs: Long?
    ): Boolean {
        if (!bindingFlag || remoteConnected) return false
        if (connectionState != "binding") return false
        val age = stateAgeMs ?: return false
        return age in 0 until BIND_TIMEOUT_MS
    }

    fun shouldResetStaleBind(
        bindingFlag: Boolean,
        remoteConnected: Boolean,
        connectionState: String,
        stateAgeMs: Long?
    ): Boolean = bindingFlag && !canReuseInFlightBind(
        bindingFlag = bindingFlag,
        remoteConnected = remoteConnected,
        connectionState = connectionState,
        stateAgeMs = stateAgeMs
    )
}
