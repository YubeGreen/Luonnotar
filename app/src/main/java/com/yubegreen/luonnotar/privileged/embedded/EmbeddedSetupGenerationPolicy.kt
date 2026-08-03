package com.yubegreen.luonnotar.privileged.embedded

/** Prevents repeated taps/notification actions from superseding the setup already in flight. */
internal object EmbeddedSetupGenerationPolicy {
    fun shouldReuseGeneration(featureEnabled: Boolean, setupState: EmbeddedSetupState): Boolean =
        featureEnabled && setupState in ACTIVE_SETUP_STATES

    private val ACTIVE_SETUP_STATES = setOf(
        EmbeddedSetupState.DISCOVERING,
        EmbeddedSetupState.WAITING_PAIRING_CODE,
        EmbeddedSetupState.STARTING
    )
}
