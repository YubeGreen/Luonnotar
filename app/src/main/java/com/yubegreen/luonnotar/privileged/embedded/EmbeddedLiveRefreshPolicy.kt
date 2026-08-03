package com.yubegreen.luonnotar.privileged.embedded

/**
 * Live status polling must not compete with the setup service while it is
 * discovering, waiting for a pairing code, or starting/configuring the engine.
 */
object EmbeddedLiveRefreshPolicy {
    fun shouldDefer(setupState: EmbeddedSetupState): Boolean = when (setupState) {
        EmbeddedSetupState.DISCOVERING,
        EmbeddedSetupState.WAITING_PAIRING_CODE,
        EmbeddedSetupState.STARTING -> true
        EmbeddedSetupState.IDLE,
        EmbeddedSetupState.FAILED -> false
    }
}
