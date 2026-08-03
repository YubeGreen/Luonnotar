package com.yubegreen.luonnotar.privileged

import android.content.Context
import android.os.SystemClock

/** Small app-UID control plane store. The privileged process owns execution. */
object PrivilegedGuardianStore {
    private const val FILE = "privileged_guardian_v2"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STATUS_JSON = "status_json"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_LAST_UPDATED_ELAPSED = "last_updated_elapsed"
    private const val KEY_CONNECTION_STATE = "connection_state"
    private const val KEY_GMS_RECOVERY_ENABLED = "gms_recovery_enabled"

    fun isEnabled(context: Context): Boolean = preferences(context)
        .getBoolean(KEY_ENABLED, false)

    fun isGmsRecoveryEnabled(context: Context): Boolean = preferences(context)
        .getBoolean(KEY_GMS_RECOVERY_ENABLED, false)

    fun setGmsRecoveryEnabled(context: Context, enabled: Boolean): Boolean = preferences(context)
        .edit()
        .putBoolean(KEY_GMS_RECOVERY_ENABLED, enabled)
        .putLong(KEY_LAST_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
        .commit()

    fun beginEnable(context: Context): Boolean = preferences(context)
        .edit()
        .putBoolean(KEY_ENABLED, true)
        .putString(KEY_CONNECTION_STATE, "starting")
        .putString(KEY_LAST_ERROR, "")
        .putLong(KEY_LAST_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
        .commit()

    fun beginDisable(context: Context): Boolean = preferences(context)
        .edit()
        .putBoolean(KEY_ENABLED, false)
        .putString(KEY_CONNECTION_STATE, "stopping")
        .putString(KEY_LAST_ERROR, "")
        .putLong(KEY_LAST_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
        .commit()

    fun statusJson(context: Context): String = preferences(context)
        .getString(KEY_STATUS_JSON, "")
        .orEmpty()

    fun lastError(context: Context): String = preferences(context)
        .getString(KEY_LAST_ERROR, "")
        .orEmpty()

    fun connectionState(context: Context): String = preferences(context)
        .getString(KEY_CONNECTION_STATE, "disconnected")
        .orEmpty()

    fun lastUpdatedElapsed(context: Context): Long = preferences(context)
        .getLong(KEY_LAST_UPDATED_ELAPSED, 0L)

    fun markDisabled(
        context: Context,
        statusJson: String = "{\"schema\":1,\"running\":false}"
    ): Boolean = preferences(context).edit()
        .putBoolean(KEY_ENABLED, false)
        .putString(KEY_STATUS_JSON, statusJson)
        .putString(KEY_LAST_ERROR, "")
        .putString(KEY_CONNECTION_STATE, "disabled")
        .putLong(KEY_LAST_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
        .commit()

    fun updateStatus(
        context: Context,
        statusJson: String,
        connectionState: String = "connected"
    ) {
        preferences(context).edit()
            .putString(KEY_STATUS_JSON, statusJson)
            .putString(KEY_LAST_ERROR, "")
            .putString(KEY_CONNECTION_STATE, connectionState)
            .putLong(KEY_LAST_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
            .apply()
    }

    fun updateConnection(context: Context, state: String, error: String = "") {
        preferences(context).edit()
            .putString(KEY_CONNECTION_STATE, state)
            .putString(KEY_LAST_ERROR, error.take(1_000))
            .putLong(KEY_LAST_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
            .apply()
    }

    private fun preferences(context: Context) = context.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
