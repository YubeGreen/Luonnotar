package com.yubegreen.luonnotar.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.yubegreen.luonnotar.service.FcmGuardianService
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences

/**
 * Shell-only laboratory bridge for triggering the in-app GMS Binder pulse
 * while the device remains locked and the screen stays off.
 *
 * The manifest protects this exported receiver with android.permission.DUMP,
 * which is held by adb shell but not by ordinary third-party applications.
 */
class AdbGmsBinderPulseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return
        val stabilization = action == ACTION_STABILIZATION_LEASE

        val appContext = context.applicationContext
        val prefs = LuonnotarPreferences.deviceProtected(appContext)
        val guardianActive =
            prefs.getBoolean(LuonnotarPreferences.KEY_ENABLED, false) &&
                !prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)

        if (!guardianActive) {
            setResultCode(Activity.RESULT_CANCELED)
            setResultData(
                "ok=false;reason=guardian_not_active;guardianActive=false;" +
                    "serviceDispatchStarted=false"
            )
            LogManager.event(
                appContext,
                "adb_gms_binder_pulse_rejected",
                mapOf("reason" to "guardian_not_active")
            )
            return
        }

        val serviceIntent =
            Intent(appContext, FcmGuardianService::class.java)
                .setAction(
                    if (stabilization) {
                        FcmGuardianService.ACTION_GMS_BINDER_STABILIZATION_LEASE
                    } else {
                        FcmGuardianService.ACTION_GMS_BINDER_PULSE_TEST
                    }
                )
                .putExtra(
                    FcmGuardianService.EXTRA_START_REASON,
                    if (stabilization) {
                        "privileged_gms_stabilization_lease"
                    } else {
                        "adb_gms_binder_pulse_test"
                    }
                )

        val started = runCatching {
            ContextCompat.startForegroundService(appContext, serviceIntent)
            true
        }.getOrElse { error ->
            LogManager.event(
                appContext,
                "adb_gms_binder_pulse_dispatch_failed",
                mapOf("error" to error.javaClass.simpleName)
            )
            false
        }

        setResultCode(
            if (started) Activity.RESULT_OK else Activity.RESULT_CANCELED
        )
        setResultData(
            "ok=$started;reason=${if (started) "" else "dispatch_failed"};" +
                "guardianActive=true;serviceDispatchStarted=$started"
        )
        LogManager.event(
            appContext,
            if (stabilization) {
                "adb_gms_binder_stabilization_requested"
            } else {
                "adb_gms_binder_pulse_requested"
            },
            mapOf(
                "guardianActive" to guardianActive,
                "serviceDispatchStarted" to started
            )
        )
    }

    companion object {
        const val ACTION_TRIGGER =
            "com.yubegreen.luonnotar.action.ADB_GMS_BINDER_PULSE_TEST"
        const val ACTION_STABILIZATION_LEASE =
            "com.yubegreen.luonnotar.action.ADB_GMS_BINDER_STABILIZATION_LEASE"
        private val SUPPORTED_ACTIONS = setOf(ACTION_TRIGGER, ACTION_STABILIZATION_LEASE)
    }
}
