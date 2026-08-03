package com.yubegreen.luonnotar.privileged.embedded

/** Prevents live refresh from recursively spawning a new setup while setup is already active or failed. */
internal object EmbeddedAutoRepairPolicy {
    fun shouldDispatch(
        featureEnabled: Boolean,
        setupState: EmbeddedSetupState,
        nowElapsed: Long,
        lastDispatchElapsed: Long,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS
    ): Boolean = featureEnabled &&
        setupState == EmbeddedSetupState.IDLE &&
        (lastDispatchElapsed <= 0L || nowElapsed - lastDispatchElapsed >= cooldownMs)

    const val DEFAULT_COOLDOWN_MS = 60_000L
}
