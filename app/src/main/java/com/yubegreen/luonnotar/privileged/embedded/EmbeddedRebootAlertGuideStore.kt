package com.yubegreen.luonnotar.privileged.embedded

import android.content.Context
import android.os.SystemClock

internal object EmbeddedRebootAlertGuideStore {
    data class SettingsReturnPlan(
        val continueEmbeddedSetup: Boolean
    )

    private const val PREFS_NAME = "embedded_reboot_alert_guide"
    private const val KEY_PERMISSION_FLOW_PENDING = "permission_flow_pending"
    private const val KEY_CONTINUE_EMBEDDED_SETUP = "continue_embedded_setup"
    private const val KEY_SETTINGS_OPENED_ELAPSED = "settings_opened_elapsed"
    private const val KEY_SETTINGS_VISITED = "settings_visited"
    private const val KEY_LAST_TEST_SENT_ELAPSED = "last_test_sent_elapsed"

    fun beginPermissionFlow(context: Context, continueEmbeddedSetup: Boolean) {
        preferences(context).edit()
            .putBoolean(KEY_PERMISSION_FLOW_PENDING, true)
            .putBoolean(KEY_CONTINUE_EMBEDDED_SETUP, continueEmbeddedSetup)
            .apply()
    }

    fun permissionFlowPending(context: Context): Boolean =
        preferences(context).getBoolean(KEY_PERMISSION_FLOW_PENDING, false)

    fun pendingContinuation(context: Context): Boolean =
        preferences(context).getBoolean(KEY_CONTINUE_EMBEDDED_SETUP, false)

    fun clearPermissionFlow(context: Context) {
        preferences(context).edit()
            .remove(KEY_PERMISSION_FLOW_PENDING)
            .remove(KEY_CONTINUE_EMBEDDED_SETUP)
            .apply()
    }

    fun markSettingsOpened(context: Context, continueEmbeddedSetup: Boolean) {
        preferences(context).edit()
            .remove(KEY_PERMISSION_FLOW_PENDING)
            .putBoolean(KEY_CONTINUE_EMBEDDED_SETUP, continueEmbeddedSetup)
            .putLong(KEY_SETTINGS_OPENED_ELAPSED, SystemClock.elapsedRealtime())
            .apply()
    }

    fun clearSettingsReturn(context: Context) {
        preferences(context).edit()
            .remove(KEY_SETTINGS_OPENED_ELAPSED)
            .remove(KEY_CONTINUE_EMBEDDED_SETUP)
            .apply()
    }

    fun consumeSettingsReturn(
        context: Context,
        nowElapsed: Long = SystemClock.elapsedRealtime()
    ): SettingsReturnPlan? {
        val prefs = preferences(context)
        val openedElapsed = prefs.getLong(KEY_SETTINGS_OPENED_ELAPSED, 0L)
        val continueSetup = prefs.getBoolean(KEY_CONTINUE_EMBEDDED_SETUP, false)
        prefs.edit()
            .remove(KEY_SETTINGS_OPENED_ELAPSED)
            .remove(KEY_CONTINUE_EMBEDDED_SETUP)
            .apply()

        if (!EmbeddedRebootAlertGuidePolicy.shouldSendTestAfterSettingsReturn(
                settingsOpenedElapsed = openedElapsed,
                nowElapsed = nowElapsed
            )
        ) return null

        prefs.edit().putBoolean(KEY_SETTINGS_VISITED, true).apply()
        return SettingsReturnPlan(continueEmbeddedSetup = continueSetup)
    }

    fun settingsVisited(context: Context): Boolean =
        preferences(context).getBoolean(KEY_SETTINGS_VISITED, false)

    fun markTestSent(
        context: Context,
        nowElapsed: Long = SystemClock.elapsedRealtime()
    ) {
        preferences(context).edit()
            .putLong(KEY_LAST_TEST_SENT_ELAPSED, nowElapsed)
            .apply()
    }

    fun lastTestSentElapsed(context: Context): Long =
        preferences(context).getLong(KEY_LAST_TEST_SENT_ELAPSED, 0L)

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
