package com.yubegreen.luonnotar.notification

import android.content.ComponentName
import android.app.Notification
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import org.json.JSONArray
import java.security.MessageDigest

class ArrivalNotificationListener : NotificationListenerService() {
    private val allowedPackages = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.google.android.gms"
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        LuonnotarPreferences.deviceProtected(this).edit()
            .putBoolean(LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_CONNECTED, true)
            .putInt(LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_PID, Process.myPid())
            .apply()
        LogManager.event(this, "notification_listener_connected")
    }

    override fun onListenerDisconnected() {
        LuonnotarPreferences.deviceProtected(this).edit()
            .putBoolean(LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_CONNECTED, false)
            .putInt(LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_PID, 0)
            .apply()
        LogManager.event(this, "notification_listener_disconnected")
        requestRebind(ComponentName(this, ArrivalNotificationListener::class.java))
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in allowedPackages) return
        val preferences = LuonnotarPreferences.deviceProtected(this)
        val acknowledged = preferences.getBoolean(
            LuonnotarPreferences.KEY_NOTIFICATION_PRIVACY_ACK,
            false
        )
        if (!acknowledged) return
        val keyHash = sha256(sbn.key)
        val groupHash = sbn.groupKey?.let(::sha256).orEmpty()
        val isGroupSummary =
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        val decision = NotificationArrivalDeduper.classify(
            recent = readRecentFingerprints(
                preferences.getString(
                    LuonnotarPreferences.KEY_NOTIFICATION_RECENT_FINGERPRINTS,
                    "[]"
                ).orEmpty()
            ),
            packageName = sbn.packageName,
            keyHash = keyHash,
            postTime = sbn.postTime
        )
        if (decision.kind == NotificationArrivalKind.DUPLICATE) {
            LogManager.event(
                this,
                "notification_arrival_duplicate",
                mapOf(
                    "packageName" to sbn.packageName,
                    "postTime" to sbn.postTime,
                    "keyHash" to keyHash
                )
            )
            return
        }
        val arrivalCount = preferences.getLong(
            LuonnotarPreferences.KEY_NOTIFICATION_COUNT,
            0L
        ) + if (decision.kind == NotificationArrivalKind.NEW) 1L else 0L
        val updateCount = preferences.getLong(
            LuonnotarPreferences.KEY_NOTIFICATION_UPDATE_COUNT,
            0L
        ) + if (decision.kind == NotificationArrivalKind.UPDATE) 1L else 0L
        preferences.edit()
            .putLong(LuonnotarPreferences.KEY_NOTIFICATION_COUNT, arrivalCount)
            .putLong(
                LuonnotarPreferences.KEY_NOTIFICATION_UPDATE_COUNT,
                updateCount
            )
            .putString(
                LuonnotarPreferences.KEY_NOTIFICATION_RECENT_FINGERPRINTS,
                JSONArray(decision.recentFingerprints).toString()
            )
            .putString(LuonnotarPreferences.KEY_LAST_NOTIFICATION_PACKAGE, sbn.packageName)
            .putLong(LuonnotarPreferences.KEY_LAST_NOTIFICATION_POST_WALL, sbn.postTime)
            .putLong(LuonnotarPreferences.KEY_LAST_NOTIFICATION_SEEN_WALL, System.currentTimeMillis())
            .putString(LuonnotarPreferences.KEY_LAST_NOTIFICATION_GROUP_HASH, groupHash)
            .putBoolean(
                LuonnotarPreferences.KEY_LAST_NOTIFICATION_IS_GROUP_SUMMARY,
                isGroupSummary
            )
            .apply()
        LogManager.event(
            this,
            if (decision.kind == NotificationArrivalKind.NEW) {
                "notification_arrival"
            } else {
                "notification_update"
            },
            mapOf(
                "packageName" to sbn.packageName,
                "postTime" to sbn.postTime,
                "keyHash" to keyHash,
                "groupHash" to groupHash,
                "isGroupSummary" to isGroupSummary,
                "arrivalCount" to arrivalCount,
                "updateCount" to updateCount
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in allowedPackages) return
        val preferences = LuonnotarPreferences.deviceProtected(this)
        val recent = readRecentFingerprints(
            preferences.getString(
                LuonnotarPreferences.KEY_NOTIFICATION_RECENT_FINGERPRINTS,
                "[]"
            ).orEmpty()
        )
        val updated = NotificationArrivalDeduper.removeKey(
            recent,
            sbn.packageName,
            sha256(sbn.key)
        )
        if (updated.size == recent.size) return
        preferences.edit()
            .putString(
                LuonnotarPreferences.KEY_NOTIFICATION_RECENT_FINGERPRINTS,
                JSONArray(updated).toString()
            )
            .apply()
    }

    private fun readRecentFingerprints(raw: String): List<String> =
        runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
}
