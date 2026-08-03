package com.yubegreen.luonnotar.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.yubegreen.luonnotar.BuildConfig
import com.yubegreen.luonnotar.notification.ArrivalNotificationListener
import com.yubegreen.luonnotar.service.GuardianStatusClient
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences

/**
 * Shell-only, privacy-safe diagnostics for the notification evidence chain.
 * The manifest protects this receiver with android.permission.DUMP.
 */
class AdbNotificationDiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val appContext = context.applicationContext
        val scanQueued = if (action == ACTION_SCAN_ACTIVE) {
            ArrivalNotificationListener.requestActiveNotificationScan("adb_manual_scan")
        } else {
            false
        }
        val status = GuardianStatusClient.status(appContext)
        val nowElapsed = SystemClock.elapsedRealtime()
        val heartbeatElapsed = status?.getLong(
            LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_HEARTBEAT_ELAPSED,
            0L
        ) ?: 0L
        val heartbeatAgeMs = if (
            heartbeatElapsed > 0L && heartbeatElapsed <= nowElapsed
        ) {
            nowElapsed - heartbeatElapsed
        } else {
            -1L
        }
        val runtimeConnected =
            ArrivalNotificationListener.isRuntimeConnected()
        val rebindRequested =
            !runtimeConnected &&
                ArrivalNotificationListener.requestExternalRebind(
                    appContext,
                    "adb_notification_diagnostic"
                )
        val result = linkedMapOf<String, Any>(
            "schema" to 1,
            "versionName" to BuildConfig.VERSION_NAME,
            "action" to action.substringAfterLast('.'),
            "ok" to (status != null),
            "privacyAcknowledged" to (status?.getBoolean(
                LuonnotarPreferences.KEY_NOTIFICATION_PRIVACY_ACK,
                false
            ) == true),
            "listenerPersistedConnected" to (status?.getBoolean(
                LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_CONNECTED,
                false
            ) == true),
            "listenerRuntimeConnected" to runtimeConnected,
            "listenerRebindRequested" to rebindRequested,
            "listenerPid" to (status?.getInt(
                LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_PID,
                0
            ) ?: 0),
            "heartbeatAgeMs" to heartbeatAgeMs,
            "liveSequence" to (status?.getLong(
                LuonnotarPreferences.KEY_PUSH_TEST_LAST_SEQUENCE,
                0L
            ) ?: 0L),
            "liveSenderEpochMs" to (status?.getLong(
                LuonnotarPreferences.KEY_PUSH_TEST_LAST_SENDER_EPOCH_MS,
                0L
            ) ?: 0L),
            "liveSeenWall" to (status?.getLong(
                LuonnotarPreferences.KEY_PUSH_TEST_LAST_SEEN_WALL,
                0L
            ) ?: 0L),
            "scanSequence" to (status?.getLong(
                LuonnotarPreferences.KEY_PUSH_TEST_SCAN_LAST_SEQUENCE,
                0L
            ) ?: 0L),
            "scanSenderEpochMs" to (status?.getLong(
                LuonnotarPreferences.KEY_PUSH_TEST_SCAN_LAST_SENDER_EPOCH_MS,
                0L
            ) ?: 0L),
            "scanNotificationPostWall" to (status?.getLong(
                LuonnotarPreferences
                    .KEY_PUSH_TEST_SCAN_LAST_NOTIFICATION_POST_WALL,
                0L
            ) ?: 0L),
            "scanSeenWall" to (status?.getLong(
                LuonnotarPreferences.KEY_PUSH_TEST_SCAN_LAST_SEEN_WALL,
                0L
            ) ?: 0L),
            "scanQueued" to scanQueued
        )
        val wireResult = result.entries.joinToString(";") { (key, value) ->
            "$key=$value"
        }
        setResultCode(if (status != null) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        setResultData(wireResult)
        LogManager.event(
            appContext,
            "adb_notification_diagnostic_requested",
            mapOf(
                "action" to action.substringAfterLast('.'),
                "statusAvailable" to (status != null),
                "privacyAcknowledged" to result["privacyAcknowledged"],
                "listenerPersistedConnected" to
                    result["listenerPersistedConnected"],
                "listenerRuntimeConnected" to
                    result["listenerRuntimeConnected"],
                "scanQueued" to scanQueued,
                "listenerRebindRequested" to rebindRequested
            )
        )
    }

    companion object {
        const val ACTION_STATUS =
            "com.yubegreen.luonnotar.action.ADB_NOTIFICATION_STATUS"
        const val ACTION_PRIVACY =
            "com.yubegreen.luonnotar.action.ADB_NOTIFICATION_PRIVACY"
        const val ACTION_SCAN_ACTIVE =
            "com.yubegreen.luonnotar.action.ADB_NOTIFICATION_SCAN_ACTIVE"
        private val SUPPORTED_ACTIONS = setOf(
            ACTION_STATUS,
            ACTION_PRIVACY,
            ACTION_SCAN_ACTIVE
        )
    }
}
