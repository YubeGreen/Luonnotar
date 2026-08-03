package com.yubegreen.luonnotar.privileged

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

data class PrivilegedGuardianSnapshot(
    val configuredEnabled: Boolean,
    val shizukuAvailable: Boolean,
    val permissionGranted: Boolean,
    val connectionState: String,
    val running: Boolean,
    val uid: Int?,
    val identity: String,
    val root: Boolean,
    val stickySupported: Boolean,
    val appHibernationSupported: Boolean,
    val eventWatcherAlive: Boolean,
    val eventTriggerCount: Long,
    val vendorSignalCount: Long,
    val vendorDeliveryFailureCount: Long,
    val vendorRecoveryPassCount: Long,
    val packageRebuildAttemptCount: Long,
    val packageRebuildSuccessCount: Long,
    val cycleCount: Long,
    val actionCount: Long,
    val errorCount: Long,
    val processCount: Int,
    val frozenProcessCount: Int,
    val lastCycleElapsed: Long,
    val gmsRecoveryEnabled: Boolean,
    val gmsRecoveryInProgress: Boolean,
    val gmsFreezeEventsInWindow: Int,
    val gmsRecoveryAttemptCount: Long,
    val gmsRecoverySuccessCount: Long,
    val gmsPidRestartCount: Long,
    val gmsTransportVerifiedRecoveryCount: Long,
    val gmsTransportObservable: Boolean,
    val gmsTransportHealthy: Boolean,
    val gmsTransportPorts: List<Int>,
    val gmsTransportConsecutiveMissing: Int,
    val gmsBadAuthenticationCount: Long,
    val lastGmsRecoveryResult: String,
    val lastGmsRecoveryTrigger: String,
    val statusAgeMs: Long?,
    val lastError: String
) {
    val privileged: Boolean get() = uid == 0 || uid == 2_000

    companion object {
        fun fromStore(
            context: Context,
            shizukuAvailable: Boolean,
            permissionGranted: Boolean
        ): PrivilegedGuardianSnapshot {
            val raw = PrivilegedGuardianStore.statusJson(context)
            val json = runCatching { JSONObject(raw) }.getOrNull()
            val processes = json?.optJSONArray("processes")
            var frozen = 0
            if (processes != null) {
                for (index in 0 until processes.length()) {
                    val item = processes.optJSONObject(index) ?: continue
                    if (!item.isNull("frozen") && item.optBoolean("frozen", false)) frozen += 1
                }
            }
            val updated = PrivilegedGuardianStore.lastUpdatedElapsed(context)
            val now = SystemClock.elapsedRealtime()
            val age = updated.takeIf { it > 0L && now >= it }?.let { now - it }
            val transport = json?.optJSONObject("gmsTransport")
            val transportPorts = buildList {
                val array = transport?.optJSONArray("establishedPorts")
                if (array != null) {
                    for (index in 0 until array.length()) add(array.optInt(index))
                }
            }
            return PrivilegedGuardianSnapshot(
                configuredEnabled = PrivilegedGuardianStore.isEnabled(context),
                shizukuAvailable = shizukuAvailable,
                permissionGranted = permissionGranted,
                connectionState = PrivilegedGuardianStore.connectionState(context),
                running = json?.optBoolean("running", false) == true,
                uid = json?.takeIf { it.has("uid") }?.optInt("uid"),
                identity = json?.optString("identity", "").orEmpty(),
                root = json?.optBoolean("root", false) == true,
                stickySupported = json?.optBoolean("supportsStickyUnfreeze", false) == true,
                appHibernationSupported =
                    json?.optBoolean("supportsAppHibernation", false) == true,
                eventWatcherAlive = json?.optBoolean("eventWatcherAlive", false) == true,
                eventTriggerCount = json?.optLong("eventTriggerCount", 0L) ?: 0L,
                vendorSignalCount = json?.optLong("vendorSignalCount", 0L) ?: 0L,
                vendorDeliveryFailureCount =
                    json?.optLong("vendorDeliveryFailureCount", 0L) ?: 0L,
                vendorRecoveryPassCount = json?.optLong("vendorRecoveryPassCount", 0L) ?: 0L,
                packageRebuildAttemptCount =
                    json?.optLong("packageRebuildAttemptCount", 0L) ?: 0L,
                packageRebuildSuccessCount =
                    json?.optLong("packageRebuildSuccessCount", 0L) ?: 0L,
                cycleCount = json?.optLong("cycleCount", 0L) ?: 0L,
                actionCount = json?.optLong("actionCount", 0L) ?: 0L,
                errorCount = json?.optLong("errorCount", 0L) ?: 0L,
                processCount = processes?.length() ?: 0,
                frozenProcessCount = frozen,
                lastCycleElapsed = json?.optLong("lastCycleElapsed", 0L) ?: 0L,
                gmsRecoveryEnabled = PrivilegedGuardianStore.isGmsRecoveryEnabled(context),
                gmsRecoveryInProgress = json?.optBoolean("gmsRecoveryInProgress", false) == true,
                gmsFreezeEventsInWindow = json?.optInt("gmsFreezeEventsInWindow", 0) ?: 0,
                gmsRecoveryAttemptCount = json?.optLong("gmsRecoveryAttemptCount", 0L) ?: 0L,
                gmsRecoverySuccessCount = json?.optLong("gmsRecoverySuccessCount", 0L) ?: 0L,
                gmsPidRestartCount = json?.optLong("gmsPidRestartCount", 0L) ?: 0L,
                gmsTransportVerifiedRecoveryCount =
                    json?.optLong("gmsTransportVerifiedRecoveryCount", 0L) ?: 0L,
                gmsTransportObservable = transport?.optBoolean("observable", false) == true,
                gmsTransportHealthy = transport?.optBoolean("healthy", false) == true,
                gmsTransportPorts = transportPorts,
                gmsTransportConsecutiveMissing =
                    transport?.optInt("consecutiveMissing", 0) ?: 0,
                gmsBadAuthenticationCount =
                    transport?.optLong("badAuthenticationCount", 0L) ?: 0L,
                lastGmsRecoveryResult = json?.optJSONObject("lastGmsRecovery")
                    ?.optString("result", "never").orEmpty(),
                lastGmsRecoveryTrigger = json?.optJSONObject("lastGmsRecovery")
                    ?.optString("trigger", "").orEmpty(),
                statusAgeMs = age,
                lastError = PrivilegedGuardianStore.lastError(context)
            )
        }
    }
}
