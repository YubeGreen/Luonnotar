package com.yubegreen.luonnotar.notification

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences

/**
 * Guardian-side recovery for a listener process that disappears without a
 * reliable onListenerDisconnected callback.
 */
object NotificationListenerRecoveryCoordinator {
    fun reconcile(context: Context): Boolean {
        val appContext = context.applicationContext
        if (
            appContext.packageName !in
            NotificationManagerCompat.getEnabledListenerPackages(appContext)
        ) return false

        val prefs = LuonnotarPreferences.deviceProtected(appContext)
        val now = SystemClock.elapsedRealtime()
        val heartbeat = prefs.getLong(
            LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_HEARTBEAT_ELAPSED,
            0L
        )
        val connected = prefs.getBoolean(
            LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_CONNECTED,
            false
        )
        val lastRequest = prefs.getLong(
            LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_REBIND_LAST_REQUEST_ELAPSED,
            0L
        )
        if (
            !NotificationListenerRecoveryPolicy.shouldRequestRebind(
                nowElapsed = now,
                connected = connected,
                heartbeatElapsed = heartbeat,
                lastRequestElapsed = lastRequest
            )
        ) return false

        val committed = prefs.edit()
            .putBoolean(
                LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_CONNECTED,
                false
            )
            .putInt(LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_PID, 0)
            .putLong(
                LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_REBIND_LAST_REQUEST_ELAPSED,
                now
            )
            .putInt(
                LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_REBIND_COUNT,
                prefs.getInt(
                    LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_REBIND_COUNT,
                    0
                ) + 1
            )
            .commit()
        if (!committed) return false

        val requested = runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(appContext, ArrivalNotificationListener::class.java)
            )
            true
        }.getOrDefault(false)
        LogManager.event(
            appContext,
            "notification_listener_guardian_rebind_requested",
            mapOf(
                "requested" to requested,
                "heartbeatAgeMs" to if (heartbeat > 0L) now - heartbeat else -1L,
                "persistedConnected" to connected
            )
        )
        return requested
    }
}
