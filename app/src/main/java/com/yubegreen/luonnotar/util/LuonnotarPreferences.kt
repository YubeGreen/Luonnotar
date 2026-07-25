package com.yubegreen.luonnotar.util

import android.content.Context
import android.content.SharedPreferences
import java.io.File

object LuonnotarPreferences {
    @Volatile
    private var initializedKeeperProcessPid = 0

    const val PREFERENCES_FILE_NAME = "luonnotar_preferences"
    const val DATASTORE_FILE_NAME = "luonnotar_settings"

    const val KEY_ENABLED = "guardian_enabled"
    const val KEY_PAUSED = "guardian_paused"
    const val KEY_RUNTIME_BOOT_ID = "runtime_boot_id"
    const val KEY_STATE = "guardian_state"
    const val KEY_PID = "guardian_pid"
    const val KEY_KEEPER_PROCESS_PID = "keeper_process_pid"
    const val KEY_PROCESS_SEQUENCE = "process_sequence"
    const val KEY_SERVICE_GENERATION = "service_generation"
    const val KEY_HTTP_EVIDENCE_GENERATION = "http_evidence_generation"
    const val KEY_SUCCESS_EVIDENCE_GENERATION = "success_evidence_generation"
    const val KEY_ATTEMPT_EVIDENCE_GENERATION = "attempt_evidence_generation"
    const val KEY_SERVICE_STARTED_ELAPSED = "service_started_elapsed"
    const val KEY_HEARTBEAT_ELAPSED = "heartbeat_elapsed"
    const val KEY_VPN = "default_is_vpn"
    const val KEY_VALIDATED = "default_validated"
    const val KEY_BYPASSABLE = "vpn_bypassable"
    const val KEY_BYPASSABLE_KNOWN = "vpn_bypassable_known"
    const val KEY_LOCKDOWN = "lockdown_enabled"
    const val KEY_LOCKDOWN_KNOWN = "lockdown_known"
    const val KEY_ALWAYS_ON = "always_on_enabled"
    const val KEY_ALWAYS_ON_KNOWN = "always_on_known"
    const val KEY_NETWORK_HANDLE = "network_handle"
    const val KEY_TRANSPORT = "underlying_transport"
    const val KEY_WAKE_LOCK = "wake_lock_held"
    const val KEY_WIFI_LOCK = "wifi_lock_held"
    const val KEY_LAST_ATTEMPT_RTT = "last_attempt_rtt_ms"
    const val KEY_LAST_ATTEMPT_ELAPSED = "last_attempt_elapsed"
    const val KEY_LAST_ATTEMPT_NETWORK_HANDLE = "last_attempt_network_handle"
    const val KEY_LAST_SUCCESS_RTT = "last_success_rtt_ms"
    const val KEY_LAST_HTTP_CODE = "last_http_code"
    const val KEY_LAST_SUCCESS_ELAPSED = "last_success_elapsed"
    const val KEY_LAST_SUCCESS_NETWORK_HANDLE = "last_success_network_handle"
    const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"
    const val KEY_LAST_ERROR = "last_error"
    const val KEY_LAST_START_REASON = "last_start_reason"
    const val KEY_LAST_BOOT_BROADCAST = "last_boot_broadcast"
    const val KEY_RECOVERY_FAILURE = "recovery_failure"
    const val KEY_RECOVERY_FAILURE_SERVICE = "recovery_failure_service"
    const val KEY_RECOVERY_FAILURE_SERVICE_ELAPSED = "recovery_failure_service_elapsed"
    const val KEY_RECOVERY_FAILURE_ALARM = "recovery_failure_alarm"
    const val KEY_RECOVERY_FAILURE_ALARM_ELAPSED = "recovery_failure_alarm_elapsed"
    const val KEY_RECOVERY_FAILURE_NOTIFICATION = "recovery_failure_notification"
    const val KEY_RECOVERY_FAILURE_NOTIFICATION_ELAPSED = "recovery_failure_notification_elapsed"
    const val KEY_RECOVERY_FAILURE_BOOT = "recovery_failure_boot"
    const val KEY_RECOVERY_FAILURE_BOOT_ELAPSED = "recovery_failure_boot_elapsed"
    const val KEY_FCM_TOKEN_REFRESH = "fcm_token_refresh"
    const val KEY_MAX_TIMER_DRIFT = "max_timer_drift_ms"
    const val KEY_LAST_TICK_UPTIME = "last_tick_uptime"
    const val KEY_LAST_TICK_ELAPSED = "last_tick_elapsed"
    const val KEY_NOTIFICATION_PRIVACY_ACK = "notification_privacy_ack"
    const val KEY_NOTIFICATION_LISTENER_CONNECTED = "notification_listener_connected"
    const val KEY_NOTIFICATION_LISTENER_PID = "notification_listener_pid"
    const val KEY_NOTIFICATION_COUNT = "notification_arrival_count"
    const val KEY_NOTIFICATION_UPDATE_COUNT = "notification_update_count"
    const val KEY_NOTIFICATION_RECENT_FINGERPRINTS = "notification_recent_fingerprints"
    const val KEY_LAST_NOTIFICATION_PACKAGE = "last_notification_package"
    const val KEY_LAST_NOTIFICATION_POST_WALL = "last_notification_post_wall"
    const val KEY_LAST_NOTIFICATION_SEEN_WALL = "last_notification_seen_wall"
    const val KEY_LAST_SERVICE_EXIT = "last_service_exit"
    const val KEY_ALARM_EXACT = "recovery_alarm_exact"
    const val KEY_ALARM_SCHEDULED_ELAPSED = "recovery_alarm_scheduled_elapsed"
    const val KEY_ADB_VERIFIED_WALL = "adb_vpn_verified_wall"
    const val KEY_ADB_VERIFIED_ELAPSED = "adb_vpn_verified_elapsed"
    const val KEY_ADB_VERIFIED_BOOT_ID = "adb_vpn_verified_boot_id"
    const val KEY_ADB_ACTIVE_VPN_PACKAGE = "adb_active_vpn_package"
    const val KEY_ADB_ALWAYS_ON = "adb_always_on"
    const val KEY_ADB_LOCKDOWN = "adb_lockdown"
    const val KEY_ADB_BYPASSABLE = "adb_bypassable"
    const val KEY_ADB_GMS_ROUTED = "adb_gms_routed"
    const val KEY_ADB_WHATSAPP_ROUTED = "adb_whatsapp_routed"
    const val KEY_ADB_WHATSAPP_BUSINESS_ROUTED = "adb_whatsapp_business_routed"
    const val KEY_ADB_INTERNET_ROUTED = "adb_internet_routed"
    const val KEY_ADB_EVIDENCE_HASH = "adb_evidence_hash"
    const val KEY_ADB_NETWORK_HANDLE = "adb_network_handle"
    const val KEY_LAST_ALERTED_STATE = "last_alerted_state"
    const val KEY_LAST_ALERT_ELAPSED = "last_alert_elapsed"
    const val KEY_HAS_EVER_OBSERVED_VPN = "has_ever_observed_vpn"
    const val KEY_LAST_NOTIFICATION_GROUP_HASH = "last_notification_group_hash"
    const val KEY_LAST_NOTIFICATION_IS_GROUP_SUMMARY = "last_notification_is_group_summary"
    const val KEY_PROBE_STARTED_ELAPSED = "probe_started_elapsed"
    const val KEY_PROBE_DEADLINE_ELAPSED = "probe_deadline_elapsed"
    const val KEY_RECOVERY_CONFIRMATION_PENDING = "recovery_confirmation_pending"
    const val KEY_RECOVERY_REQUESTED_ELAPSED = "recovery_requested_elapsed"
    const val KEY_LAST_RECOVERY_SUCCESS_ELAPSED = "last_recovery_success_elapsed"
    const val KEY_ALARM_INSURANCE = "recovery_alarm_insurance"

    fun deviceProtected(context: Context): SharedPreferences {
        val storage = context.createDeviceProtectedStorageContext()
        return storage.getSharedPreferences(PREFERENCES_FILE_NAME, Context.MODE_PRIVATE)
    }

    fun initializeKeeperBoot(context: Context): Boolean {
        val currentBootId = runCatching {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        }.getOrDefault("")
        if (currentBootId.isBlank()) return false
        val prefs = deviceProtected(context)
        if (prefs.getString(KEY_RUNTIME_BOOT_ID, "") == currentBootId) return true
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val paused = prefs.getBoolean(KEY_PAUSED, false)
        val editor = prefs.edit()
            .putString(KEY_RUNTIME_BOOT_ID, currentBootId)
            .putInt(KEY_PID, 0)
            .putBoolean(KEY_WAKE_LOCK, false)
            .putBoolean(KEY_WIFI_LOCK, false)
            .putBoolean(KEY_NOTIFICATION_LISTENER_CONNECTED, false)
            .putInt(KEY_NOTIFICATION_LISTENER_PID, 0)
            .putString(
                KEY_STATE,
                when {
                    !enabled -> "DISABLED"
                    paused -> "PAUSED"
                    else -> "STARTING"
                }
            )
            .remove(KEY_SERVICE_STARTED_ELAPSED)
            .remove(KEY_HEARTBEAT_ELAPSED)
            .remove(KEY_LAST_TICK_ELAPSED)
            .remove(KEY_LAST_TICK_UPTIME)
            .remove(KEY_MAX_TIMER_DRIFT)
            .remove(KEY_LAST_ATTEMPT_RTT)
            .remove(KEY_LAST_ATTEMPT_ELAPSED)
            .remove(KEY_LAST_ATTEMPT_NETWORK_HANDLE)
            .remove(KEY_LAST_SUCCESS_RTT)
            .remove(KEY_LAST_SUCCESS_ELAPSED)
            .remove(KEY_LAST_SUCCESS_NETWORK_HANDLE)
            .remove(KEY_LAST_HTTP_CODE)
            .remove(KEY_CONSECUTIVE_FAILURES)
            .remove(KEY_HTTP_EVIDENCE_GENERATION)
            .remove(KEY_SUCCESS_EVIDENCE_GENERATION)
            .remove(KEY_ATTEMPT_EVIDENCE_GENERATION)
            .remove(KEY_LAST_ERROR)
            .remove(KEY_LAST_START_REASON)
            .remove(KEY_VPN)
            .remove(KEY_VALIDATED)
            .remove(KEY_BYPASSABLE)
            .remove(KEY_BYPASSABLE_KNOWN)
            .remove(KEY_NETWORK_HANDLE)
            .remove(KEY_HAS_EVER_OBSERVED_VPN)
            .remove(KEY_TRANSPORT)
            .remove(KEY_LOCKDOWN)
            .remove(KEY_LOCKDOWN_KNOWN)
            .remove(KEY_ALWAYS_ON)
            .remove(KEY_ALWAYS_ON_KNOWN)
            .remove(KEY_ALARM_EXACT)
            .remove(KEY_ALARM_SCHEDULED_ELAPSED)
            .remove(KEY_LAST_BOOT_BROADCAST)
            .remove(KEY_ADB_VERIFIED_WALL)
            .remove(KEY_ADB_VERIFIED_ELAPSED)
            .remove(KEY_ADB_VERIFIED_BOOT_ID)
            .remove(KEY_ADB_ACTIVE_VPN_PACKAGE)
            .remove(KEY_ADB_ALWAYS_ON)
            .remove(KEY_ADB_LOCKDOWN)
            .remove(KEY_ADB_BYPASSABLE)
            .remove(KEY_ADB_GMS_ROUTED)
            .remove(KEY_ADB_WHATSAPP_ROUTED)
            .remove(KEY_ADB_WHATSAPP_BUSINESS_ROUTED)
            .remove(KEY_ADB_INTERNET_ROUTED)
            .remove(KEY_ADB_EVIDENCE_HASH)
            .remove(KEY_ADB_NETWORK_HANDLE)
        if (enabled && !paused) {
            editor
                .putString(KEY_LAST_SERVICE_EXIT, "boot_cycle_changed")
                .putString(
                    KEY_RECOVERY_FAILURE_SERVICE,
                    "检测到新开机周期；尚无本次开机的守护恢复证据"
                )
                .putLong(
                    KEY_RECOVERY_FAILURE_SERVICE_ELAPSED,
                    android.os.SystemClock.elapsedRealtime()
                )
        } else {
            editor
                .remove(KEY_RECOVERY_FAILURE_SERVICE)
                .remove(KEY_RECOVERY_FAILURE_SERVICE_ELAPSED)
        }
        return editor.commit()
    }

    @Synchronized
    fun initializeKeeperProcess(context: Context): Boolean {
        val currentPid = android.os.Process.myPid()
        if (initializedKeeperProcessPid == currentPid) return true
        val prefs = deviceProtected(context)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val paused = prefs.getBoolean(KEY_PAUSED, false)
        val editor = prefs.edit()
            .putInt(KEY_KEEPER_PROCESS_PID, currentPid)
            .putInt(KEY_PID, 0)
            .putBoolean(KEY_WAKE_LOCK, false)
            .putBoolean(KEY_WIFI_LOCK, false)
            .putBoolean(KEY_NOTIFICATION_LISTENER_CONNECTED, false)
            .putInt(KEY_NOTIFICATION_LISTENER_PID, 0)
            .putString(
                KEY_STATE,
                when {
                    !enabled -> "DISABLED"
                    paused -> "PAUSED"
                    else -> "RECOVERING"
                }
            )
            .remove(KEY_SERVICE_STARTED_ELAPSED)
            .remove(KEY_HEARTBEAT_ELAPSED)
            .remove(KEY_LAST_TICK_ELAPSED)
            .remove(KEY_LAST_TICK_UPTIME)
            .remove(KEY_MAX_TIMER_DRIFT)
            .remove(KEY_LAST_ATTEMPT_RTT)
            .remove(KEY_LAST_ATTEMPT_ELAPSED)
            .remove(KEY_LAST_ATTEMPT_NETWORK_HANDLE)
            .remove(KEY_LAST_SUCCESS_RTT)
            .remove(KEY_LAST_SUCCESS_ELAPSED)
            .remove(KEY_LAST_SUCCESS_NETWORK_HANDLE)
            .remove(KEY_LAST_HTTP_CODE)
            .remove(KEY_CONSECUTIVE_FAILURES)
            .remove(KEY_HTTP_EVIDENCE_GENERATION)
            .remove(KEY_SUCCESS_EVIDENCE_GENERATION)
            .remove(KEY_ATTEMPT_EVIDENCE_GENERATION)
            .remove(KEY_LAST_ERROR)
            .remove(KEY_VPN)
            .remove(KEY_VALIDATED)
            .remove(KEY_BYPASSABLE)
            .remove(KEY_BYPASSABLE_KNOWN)
            .remove(KEY_NETWORK_HANDLE)
            .remove(KEY_TRANSPORT)
            .remove(KEY_LOCKDOWN)
            .remove(KEY_LOCKDOWN_KNOWN)
            .remove(KEY_ALWAYS_ON)
            .remove(KEY_ALWAYS_ON_KNOWN)
            .remove(KEY_ADB_VERIFIED_WALL)
            .remove(KEY_ADB_VERIFIED_ELAPSED)
            .remove(KEY_ADB_VERIFIED_BOOT_ID)
            .remove(KEY_ADB_ACTIVE_VPN_PACKAGE)
            .remove(KEY_ADB_ALWAYS_ON)
            .remove(KEY_ADB_LOCKDOWN)
            .remove(KEY_ADB_BYPASSABLE)
            .remove(KEY_ADB_GMS_ROUTED)
            .remove(KEY_ADB_WHATSAPP_ROUTED)
            .remove(KEY_ADB_WHATSAPP_BUSINESS_ROUTED)
            .remove(KEY_ADB_INTERNET_ROUTED)
            .remove(KEY_ADB_EVIDENCE_HASH)
            .remove(KEY_ADB_NETWORK_HANDLE)
        if (enabled && !paused) {
            val recoveryRequestedElapsed = android.os.SystemClock.elapsedRealtime()
            editor
                .putString(
                    KEY_RECOVERY_FAILURE_SERVICE,
                    "Keeper 进程已重建；等待新的服务心跳与网络证据"
                )
                .putLong(
                    KEY_RECOVERY_FAILURE_SERVICE_ELAPSED,
                    recoveryRequestedElapsed
                )
                .putBoolean(KEY_RECOVERY_CONFIRMATION_PENDING, true)
                .putLong(KEY_RECOVERY_REQUESTED_ELAPSED, recoveryRequestedElapsed)
        } else {
            editor
                .putBoolean(KEY_RECOVERY_CONFIRMATION_PENDING, false)
                .remove(KEY_RECOVERY_REQUESTED_ELAPSED)
        }
        val committed = editor.commit()
        if (committed) initializedKeeperProcessPid = currentPid
        return committed
    }
}
