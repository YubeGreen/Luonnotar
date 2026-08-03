package com.yubegreen.luonnotar.privileged.embedded

internal object EmbeddedGuardianNotificationPolicy {
    enum class PendingIntentKind { ACTIVITY }
    enum class Destination { MAIN_ACTIVITY, ACTION_ACTIVITY }

    data class PendingIntentSpec(
        val kind: PendingIntentKind,
        val destination: Destination,
        val action: String?,
        val requestCode: Int
    )

    const val REBOOT_CONTENT_REQUEST_CODE = 46
    const val REBOOT_START_REQUEST_CODE = 47
    const val PAIRING_REQUEST_CODE = 41
    const val RETRY_REQUEST_CODE = 42
    const val WIRELESS_REQUEST_CODE = 43
    const val SETUP_CONTENT_REQUEST_CODE = 45

    val rebootContentSpec = PendingIntentSpec(
        PendingIntentKind.ACTIVITY,
        Destination.MAIN_ACTIVITY,
        action = null,
        requestCode = REBOOT_CONTENT_REQUEST_CODE
    )

    val rebootStartSpec = PendingIntentSpec(
        PendingIntentKind.ACTIVITY,
        Destination.ACTION_ACTIVITY,
        action = "com.yubegreen.luonnotar.action.START_EMBEDDED_GUARDIAN",
        requestCode = REBOOT_START_REQUEST_CODE
    )

    val wirelessSettingsActions = listOf(
        "android.settings.WIRELESS_DEBUGGING_SETTINGS",
        "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"
    )

    fun shouldStartSetup(action: String?): Boolean =
        action == rebootStartSpec.action

    fun executeStartAction(
        action: String?,
        startSetup: () -> Unit,
        onStartFailure: (Throwable) -> Unit,
        openWirelessSettings: () -> Unit
    ): Boolean {
        if (!shouldStartSetup(action)) return false
        runCatching(startSetup).onFailure(onStartFailure)
        openWirelessSettings()
        return true
    }

    fun fallsBackToMainActivity(): Boolean = true
}
