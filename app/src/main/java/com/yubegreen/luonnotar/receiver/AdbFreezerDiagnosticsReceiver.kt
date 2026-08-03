package com.yubegreen.luonnotar.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.yubegreen.luonnotar.monitor.TargetUidHealthSnapshot
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import org.json.JSONObject
import org.json.JSONArray

class AdbFreezerDiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_IMPORT) return
        val targetSnapshotJson = decodedExtra(
            intent,
            EXTRA_TARGET_UID_SNAPSHOT,
            EXTRA_TARGET_UID_SNAPSHOT_BASE64
        )
        val snapshots = runCatching {
            TargetUidHealthSnapshot.parseArray(targetSnapshotJson)
        }.getOrDefault(emptyList())
        val captureStarted = intent.getLongExtra(
            EXTRA_CAPTURE_STARTED,
            -1L
        )
        val captureFinished = intent.getLongExtra(
            EXTRA_CAPTURE_FINISHED,
            -1L
        )
        val importedAt = System.currentTimeMillis()
        val captureTimesValid =
            captureStarted > 0L &&
                captureFinished >= captureStarted &&
                captureFinished <= importedAt + CAPTURE_CLOCK_TOLERANCE_MS
        val eventCounts = runCatching {
            JSONObject(
                decodedExtra(
                    intent,
                    EXTRA_EVENT_COUNTS,
                    EXTRA_EVENT_COUNTS_BASE64
                )
            )
        }.getOrNull()
        val allowedEventTypes = allowedEventTypes()
        val sanitizedEventCounts =
            eventCounts?.keys()?.asSequence()
                ?.filter { it in allowedEventTypes }
                ?.associateWith { eventCounts.optInt(it, 0).coerceAtLeast(0) }
                .orEmpty()
        val eventTimeline = parseEventTimeline(
            decodedExtra(
                intent,
                EXTRA_EVENT_TIMELINE,
                EXTRA_EVENT_TIMELINE_BASE64
            )
        )
        LogManager.timeline(
            context,
            "adb_freezer_diagnostics_imported",
            mapOf(
                "sessionId" to
                    intent.getStringExtra(EXTRA_SESSION_ID)
                        .orEmpty()
                        .take(80),
                "phase" to
                    intent.getStringExtra(EXTRA_PHASE)
                        .orEmpty()
                        .take(16),
                "quickFrozenMatches" to
                    intent.getIntExtra(EXTRA_QUICK_FROZEN_COUNT, 0),
                "frozenMatches" to
                    intent.getIntExtra(EXTRA_FROZEN_COUNT, 0),
                "luonnotarFrozen" to
                    intent.getBooleanExtra(EXTRA_LUONNOTAR_FROZEN, false),
                "tailscaleFrozen" to
                    intent.getBooleanExtra(EXTRA_TAILSCALE_FROZEN, false),
                "gmsFrozen" to
                    intent.getBooleanExtra(EXTRA_GMS_FROZEN, false),
                "whatsappFrozen" to
                    intent.getBooleanExtra(EXTRA_WHATSAPP_FROZEN, false),
                "whatsappBusinessFrozen" to
                    intent.getBooleanExtra(
                        EXTRA_WHATSAPP_BUSINESS_FROZEN,
                        false
                    ),
                "aospFreezerMode" to
                    intent.getStringExtra(EXTRA_AOSP_FREEZER_MODE)
                        .orEmpty()
                        .take(32),
                "targetSnapshotCount" to snapshots.size,
                "eventCounts" to sanitizedEventCounts,
                "importedTimelineEvents" to eventTimeline.size
            )
        )
        snapshots.forEach { snapshot ->
            LogManager.timeline(
                context,
                "target_uid_health_snapshot",
                snapshot.toTimelineMap()
            )
        }
        eventTimeline.forEach { event ->
            LogManager.timeline(
                context,
                "iqoo_system_event",
                event
            )
        }
        if (snapshots.isNotEmpty()) {
            val sanitizedSnapshots =
                TargetUidHealthSnapshot.toSanitizedArray(snapshots)
            val editor =
                LuonnotarPreferences.deviceProtected(context).edit()
                .putString(
                    LuonnotarPreferences.KEY_TARGET_UID_HEALTH_SNAPSHOT,
                    sanitizedSnapshots
                )
                .putLong(
                    LuonnotarPreferences.KEY_TARGET_UID_HEALTH_IMPORTED_AT,
                    importedAt
                )
                .putString(
                    LuonnotarPreferences.KEY_TARGET_UID_HEALTH_IMPORT_STATE,
                    if (captureTimesValid) "CURRENT" else "CURRENT_TIME_UNKNOWN"
                )
            if (captureTimesValid) {
                editor
                    .putLong(
                        LuonnotarPreferences
                            .KEY_TARGET_UID_HEALTH_CAPTURED_WALL,
                        captureFinished
                    )
                    .putLong(
                        LuonnotarPreferences
                            .KEY_TARGET_UID_HEALTH_CAPTURE_STARTED,
                        captureStarted
                    )
                    .putLong(
                        LuonnotarPreferences
                            .KEY_TARGET_UID_HEALTH_CAPTURE_FINISHED,
                        captureFinished
                    )
            } else {
                editor
                    .remove(
                        LuonnotarPreferences
                            .KEY_TARGET_UID_HEALTH_CAPTURED_WALL
                    )
                    .remove(
                        LuonnotarPreferences
                            .KEY_TARGET_UID_HEALTH_CAPTURE_STARTED
                    )
                    .remove(
                        LuonnotarPreferences
                            .KEY_TARGET_UID_HEALTH_CAPTURE_FINISHED
                    )
            }
            editor.apply()
        } else {
            LuonnotarPreferences.deviceProtected(context).edit()
                .remove(
                    LuonnotarPreferences.KEY_TARGET_UID_HEALTH_SNAPSHOT
                )
                .remove(
                    LuonnotarPreferences
                        .KEY_TARGET_UID_HEALTH_CAPTURED_WALL
                )
                .putString(
                    LuonnotarPreferences.KEY_TARGET_UID_HEALTH_IMPORT_STATE,
                    "SNAPSHOT_IMPORT_FAILED"
                )
                .putLong(
                    LuonnotarPreferences.KEY_TARGET_UID_HEALTH_IMPORTED_AT,
                    importedAt
                )
                .remove(
                    LuonnotarPreferences
                        .KEY_TARGET_UID_HEALTH_CAPTURE_STARTED
                )
                .remove(
                    LuonnotarPreferences
                        .KEY_TARGET_UID_HEALTH_CAPTURE_FINISHED
                )
                .apply()
        }
    }

    private fun decodedExtra(
        intent: Intent,
        plainName: String,
        base64Name: String
    ): String {
        val encoded = intent.getStringExtra(base64Name).orEmpty()
        if (encoded.isNotBlank() && encoded.length <= MAX_BASE64_EXTRA_LENGTH) {
            return runCatching {
                String(
                    Base64.decode(encoded, Base64.DEFAULT),
                    Charsets.UTF_8
                )
            }.getOrDefault("")
        }
        return intent.getStringExtra(plainName).orEmpty()
    }

    private fun parseEventTimeline(raw: String): List<Map<String, Any?>> {
        if (raw.isBlank() || raw.length > 64_000) return emptyList()
        val allowedTypes = allowedEventTypes()
        val allowedPackages = setOf(
            "",
            "com.yubegreen.luonnotar",
            "ch.protonvpn.android",
            "com.tailscale.ipn",
            "com.termux",
            "com.termux.boot",
            "com.google.android.gms",
            "com.whatsapp",
            "com.whatsapp.w4b"
        )
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val type = item.optString("eventType").take(48)
                    val target = item.optString("targetPackage").take(120)
                    if (type !in allowedTypes || target !in allowedPackages) {
                        continue
                    }
                    add(
                        mapOf(
                            "systemEventEpochMs" to
                                item.optLong("epochMs", -1L),
                            "systemEventMonotonicMs" to
                                item.optLong("monotonicMs", -1L),
                            "systemEventSequence" to
                                item.optLong("sequence", index.toLong()),
                            "systemEventType" to type,
                            "targetPackage" to target,
                            "systemEventLineHash" to
                                item.optString("lineHash").take(64)
                        )
                    )
                }
            }.sortedWith(
                compareBy<Map<String, Any?>> {
                    it["systemEventEpochMs"] as? Long ?: -1L
                }.thenBy {
                    it["systemEventMonotonicMs"] as? Long ?: -1L
                }.thenBy {
                    it["systemEventSequence"] as? Long ?: -1L
                }
            ).takeLast(200)
        }.getOrDefault(emptyList())
    }

    private fun allowedEventTypes(): Set<String> = setOf(
            "fast_freezer",
            "QuickFrozen",
            "single_cleaner",
            "am_app_frozen",
            "am_app_unfrozen",
            "am_kill",
            "am_proc_start",
            "am_uid_stopped",
            "GCM_HB_ALARM",
            "FcmRetry",
            "C2DM_RECEIVE",
            "FirebaseInstanceIdReceiver",
            "GcmFGService",
            "MessageService",
            "XmppLifecycleWorker",
            "notification_enqueue"
        )

    companion object {
        private const val CAPTURE_CLOCK_TOLERANCE_MS = 5 * 60_000L
        const val ACTION_IMPORT =
            "com.yubegreen.luonnotar.action.ADB_IMPORT_FREEZER"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_QUICK_FROZEN_COUNT = "quickfrozen_count"
        const val EXTRA_FROZEN_COUNT = "frozen_count"
        const val EXTRA_LUONNOTAR_FROZEN = "luonnotar_frozen"
        const val EXTRA_TAILSCALE_FROZEN = "tailscale_frozen"
        const val EXTRA_GMS_FROZEN = "gms_frozen"
        const val EXTRA_WHATSAPP_FROZEN = "whatsapp_frozen"
        const val EXTRA_WHATSAPP_BUSINESS_FROZEN =
            "whatsapp_business_frozen"
        const val EXTRA_AOSP_FREEZER_MODE = "aosp_freezer_mode"
        const val EXTRA_TARGET_UID_SNAPSHOT = "target_uid_snapshot"
        const val EXTRA_TARGET_UID_SNAPSHOT_BASE64 =
            "target_uid_snapshot_b64"
        const val EXTRA_EVENT_COUNTS = "event_counts"
        const val EXTRA_EVENT_COUNTS_BASE64 = "event_counts_b64"
        const val EXTRA_EVENT_TIMELINE = "event_timeline"
        const val EXTRA_EVENT_TIMELINE_BASE64 = "event_timeline_b64"
        const val EXTRA_CAPTURE_STARTED = "capture_started"
        const val EXTRA_CAPTURE_FINISHED = "capture_finished"
        private const val MAX_BASE64_EXTRA_LENGTH = 128_000
    }
}
