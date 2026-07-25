package com.yubegreen.luonnotar.service

import android.content.Context
import android.net.Uri
import android.os.Bundle

object GuardianStatusClient {
    fun status(context: Context): Bundle? = call(context, GuardianStatusProvider.METHOD_STATUS)

    fun setEnabled(context: Context, enabled: Boolean): Boolean =
        call(
            context,
            GuardianStatusProvider.METHOD_SET_ENABLED,
            Bundle().apply { putBoolean(GuardianStatusProvider.EXTRA_VALUE, enabled) }
        )?.getBoolean(GuardianStatusProvider.RESULT_OK) == true

    fun setPrivacyAcknowledged(context: Context): Boolean =
        call(context, GuardianStatusProvider.METHOD_ACK_NOTIFICATION_PRIVACY)
            ?.getBoolean(GuardianStatusProvider.RESULT_OK) == true

    fun setAggressiveMode(context: Context, enabled: Boolean): Boolean =
        call(
            context,
            GuardianStatusProvider.METHOD_SET_AGGRESSIVE_MODE,
            Bundle().apply { putBoolean(GuardianStatusProvider.EXTRA_VALUE, enabled) }
        )?.getBoolean(GuardianStatusProvider.RESULT_OK) == true

    fun rejectPolicy(context: Context): Boolean =
        call(context, GuardianStatusProvider.METHOD_REJECT_POLICY)
            ?.getBoolean(GuardianStatusProvider.RESULT_OK) == true

    fun setRecoveryFailure(
        context: Context,
        value: String,
        source: String = GuardianStatusProvider.SOURCE_SERVICE
    ): Boolean =
        call(
            context,
            GuardianStatusProvider.METHOD_SET_RECOVERY_FAILURE,
            Bundle().apply {
                putString(GuardianStatusProvider.EXTRA_VALUE, value)
                putString(GuardianStatusProvider.EXTRA_SOURCE, source)
            }
        )?.getBoolean(GuardianStatusProvider.RESULT_OK) == true

    fun recordBootAction(context: Context, action: String): Boolean =
        call(
            context,
            GuardianStatusProvider.METHOD_RECORD_BOOT,
            Bundle().apply { putString(GuardianStatusProvider.EXTRA_VALUE, action) }
        )?.getBoolean(GuardianStatusProvider.RESULT_OK) == true

    fun scheduleRecoveryAlarm(context: Context): Boolean =
        call(context, GuardianStatusProvider.METHOD_SCHEDULE_RECOVERY_ALARM)
            ?.getBoolean(GuardianStatusProvider.RESULT_OK) == true

    fun clearAdbEvidence(context: Context): Boolean =
        call(context, GuardianStatusProvider.METHOD_CLEAR_ADB_EVIDENCE)
            ?.getBoolean(GuardianStatusProvider.RESULT_OK) == true

    private fun call(context: Context, method: String, extras: Bundle? = null): Bundle? =
        runCatching {
            context.contentResolver.call(
                Uri.parse("content://${context.packageName}.status"),
                method,
                null,
                extras
            )
        }.getOrNull()
}
