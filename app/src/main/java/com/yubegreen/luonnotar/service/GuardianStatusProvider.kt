package com.yubegreen.luonnotar.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process
import com.yubegreen.luonnotar.monitor.GuardianState
import com.yubegreen.luonnotar.receiver.LabAlarmScheduler
import com.yubegreen.luonnotar.util.LuonnotarPreferences

class GuardianStatusProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val providerContext = context ?: return false
        LuonnotarPreferences.initializeKeeperBoot(providerContext)
        return LuonnotarPreferences.initializeKeeperProcess(providerContext)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context ?: return Bundle.EMPTY
        val prefs = LuonnotarPreferences.deviceProtected(context)
        if (method == METHOD_STATUS) {
            return Bundle().apply {
                prefs.all.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                    }
                }
                putInt(LuonnotarPreferences.KEY_KEEPER_PROCESS_PID, Process.myPid())
            }
        }
        val ok = when (method) {
            METHOD_SET_ENABLED -> {
                val enabled = extras?.getBoolean(EXTRA_VALUE) ?: return result(false)
                val editor = prefs.edit()
                    .putBoolean(LuonnotarPreferences.KEY_ENABLED, enabled)
                    .putBoolean(LuonnotarPreferences.KEY_PAUSED, false)
                    .putString(
                        LuonnotarPreferences.KEY_STATE,
                        if (enabled) GuardianState.STARTING.name else GuardianState.DISABLED.name
                    )
                if (!enabled) {
                    editor
                        .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE)
                        .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE_ELAPSED)
                        .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM)
                        .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM_ELAPSED)
                        .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_NOTIFICATION)
                        .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_NOTIFICATION_ELAPSED)
                        .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_BOOT)
                        .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_BOOT_ELAPSED)
                }
                val committed = editor.commit()
                if (!committed) {
                    false
                } else if (!enabled) {
                    runCatching { LabAlarmScheduler.cancel(context) }
                    true
                } else {
                    val scheduled = runCatching {
                        LabAlarmScheduler.scheduleNext(context)
                    }.getOrDefault(false)
                    if (!scheduled) {
                        prefs.edit()
                            .putString(
                                LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM,
                                "恢复闹钟安排失败；前台守护仍已启用"
                            )
                            .putLong(
                                LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM_ELAPSED,
                                android.os.SystemClock.elapsedRealtime()
                            )
                            .commit()
                    }
                    true
                }
            }
            METHOD_ACK_NOTIFICATION_PRIVACY ->
                prefs.edit().putBoolean(
                    LuonnotarPreferences.KEY_NOTIFICATION_PRIVACY_ACK,
                    true
                ).commit()
            METHOD_REJECT_POLICY -> {
                val committed = prefs.edit()
                    .putBoolean(LuonnotarPreferences.KEY_ENABLED, false)
                    .putBoolean(LuonnotarPreferences.KEY_PAUSED, false)
                    .putBoolean(LuonnotarPreferences.KEY_NOTIFICATION_PRIVACY_ACK, false)
                    .putBoolean(LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_CONNECTED, false)
                    .putInt(LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_PID, 0)
                    .putString(LuonnotarPreferences.KEY_STATE, GuardianState.DISABLED.name)
                    .remove(LuonnotarPreferences.KEY_NOTIFICATION_RECENT_FINGERPRINTS)
                    .commit()
                runCatching { LabAlarmScheduler.cancel(context) }
                committed
            }
            METHOD_SET_RECOVERY_FAILURE -> {
                val value = extras?.getString(EXTRA_VALUE).orEmpty()
                val (valueKey, elapsedKey) = when (
                    extras?.getString(EXTRA_SOURCE)
                ) {
                    SOURCE_ALARM ->
                        LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM to
                            LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM_ELAPSED
                    SOURCE_NOTIFICATION ->
                        LuonnotarPreferences.KEY_RECOVERY_FAILURE_NOTIFICATION to
                            LuonnotarPreferences.KEY_RECOVERY_FAILURE_NOTIFICATION_ELAPSED
                    SOURCE_BOOT ->
                        LuonnotarPreferences.KEY_RECOVERY_FAILURE_BOOT to
                            LuonnotarPreferences.KEY_RECOVERY_FAILURE_BOOT_ELAPSED
                    else ->
                        LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE to
                            LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE_ELAPSED
                }
                val editor = prefs.edit()
                if (value.isBlank()) {
                    editor.remove(valueKey).remove(elapsedKey)
                } else {
                    editor.putString(valueKey, value)
                        .putLong(elapsedKey, android.os.SystemClock.elapsedRealtime())
                }
                editor.commit()
            }
            METHOD_RECORD_BOOT ->
                prefs.edit().putString(
                    LuonnotarPreferences.KEY_LAST_BOOT_BROADCAST,
                    extras?.getString(EXTRA_VALUE).orEmpty()
                ).commit()
            METHOD_SCHEDULE_RECOVERY_ALARM -> {
                runCatching { LabAlarmScheduler.scheduleNext(context) }.getOrDefault(false)
            }
            METHOD_CLEAR_ADB_EVIDENCE -> {
                prefs.edit()
                    .remove(LuonnotarPreferences.KEY_ADB_VERIFIED_WALL)
                    .remove(LuonnotarPreferences.KEY_ADB_VERIFIED_ELAPSED)
                    .remove(LuonnotarPreferences.KEY_ADB_VERIFIED_BOOT_ID)
                    .remove(LuonnotarPreferences.KEY_ADB_ACTIVE_VPN_PACKAGE)
                    .remove(LuonnotarPreferences.KEY_ADB_ALWAYS_ON)
                    .remove(LuonnotarPreferences.KEY_ADB_LOCKDOWN)
                    .remove(LuonnotarPreferences.KEY_ADB_BYPASSABLE)
                    .remove(LuonnotarPreferences.KEY_ADB_GMS_ROUTED)
                    .remove(LuonnotarPreferences.KEY_ADB_WHATSAPP_ROUTED)
                    .remove(LuonnotarPreferences.KEY_ADB_WHATSAPP_BUSINESS_ROUTED)
                    .remove(LuonnotarPreferences.KEY_ADB_INTERNET_ROUTED)
                    .remove(LuonnotarPreferences.KEY_ADB_EVIDENCE_HASH)
                    .remove(LuonnotarPreferences.KEY_ADB_NETWORK_HANDLE)
                    .commit()
            }
            else -> false
        }
        return result(ok)
    }

    private fun result(ok: Boolean) = Bundle().apply { putBoolean(RESULT_OK, ok) }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val METHOD_STATUS = "guardian_status"
        const val METHOD_SET_ENABLED = "set_enabled"
        const val METHOD_ACK_NOTIFICATION_PRIVACY = "ack_notification_privacy"
        const val METHOD_REJECT_POLICY = "reject_policy"
        const val METHOD_SET_RECOVERY_FAILURE = "set_recovery_failure"
        const val METHOD_RECORD_BOOT = "record_boot"
        const val METHOD_SCHEDULE_RECOVERY_ALARM = "schedule_recovery_alarm"
        const val METHOD_CLEAR_ADB_EVIDENCE = "clear_adb_evidence"
        const val EXTRA_VALUE = "value"
        const val EXTRA_SOURCE = "source"
        const val SOURCE_SERVICE = "service"
        const val SOURCE_ALARM = "alarm"
        const val SOURCE_NOTIFICATION = "notification"
        const val SOURCE_BOOT = "boot"
        const val RESULT_OK = "ok"
    }
}
