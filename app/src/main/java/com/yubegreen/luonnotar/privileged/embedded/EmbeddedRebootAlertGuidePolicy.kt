package com.yubegreen.luonnotar.privileged.embedded

internal object EmbeddedRebootAlertGuidePolicy {
    const val SETTINGS_RETURN_MAX_AGE_MS = 10 * 60_000L

    fun requiresRuntimePermission(
        sdkInt: Int,
        runtimePermissionGranted: Boolean
    ): Boolean =
        sdkInt >= 33 && !runtimePermissionGranted

    fun requiresSettingsVisit(
        sdkInt: Int,
        runtimePermissionGranted: Boolean,
        notificationsEnabled: Boolean,
        channelEnabled: Boolean,
        settingsVisited: Boolean
    ): Boolean =
        !requiresRuntimePermission(sdkInt, runtimePermissionGranted) &&
            (!notificationsEnabled || !channelEnabled || !settingsVisited)

    fun shouldRunGuide(
        sdkInt: Int,
        runtimePermissionGranted: Boolean,
        notificationsEnabled: Boolean,
        channelEnabled: Boolean,
        settingsVisited: Boolean
    ): Boolean =
        requiresRuntimePermission(sdkInt, runtimePermissionGranted) ||
            requiresSettingsVisit(
                sdkInt,
                runtimePermissionGranted,
                notificationsEnabled,
                channelEnabled,
                settingsVisited
            )

    fun shouldSendTestAfterSettingsReturn(
        settingsOpenedElapsed: Long,
        nowElapsed: Long
    ): Boolean =
        settingsOpenedElapsed > 0L &&
            nowElapsed >= settingsOpenedElapsed &&
            nowElapsed - settingsOpenedElapsed <= SETTINGS_RETURN_MAX_AGE_MS
}
