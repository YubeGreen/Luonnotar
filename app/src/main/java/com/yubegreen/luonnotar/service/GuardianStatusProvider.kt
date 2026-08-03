package com.yubegreen.luonnotar.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.yubegreen.luonnotar.experiment.ExperimentSessionOperationResult
import com.yubegreen.luonnotar.experiment.ExperimentSessionRecorder
import com.yubegreen.luonnotar.monitor.GuardianState
import com.yubegreen.luonnotar.notification.NotificationArrivalDeduper
import com.yubegreen.luonnotar.notification.NotificationArrivalKind
import com.yubegreen.luonnotar.notification.PushTestDeliveryPolicy
import com.yubegreen.luonnotar.receiver.LabAlarmScheduler
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import org.json.JSONArray
import java.io.File

class GuardianStatusProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val providerContext = context ?: return false
        LuonnotarPreferences.initializeKeeperBoot(providerContext)
        val initialized =
            LuonnotarPreferences.initializeKeeperProcess(providerContext)
        GuardianProfilePolicy.ensureDefaults(
            providerContext,
            LuonnotarPreferences.deviceProtected(providerContext)
        )
        return initialized
    }

    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context ?: return Bundle.EMPTY
        val prefs = LuonnotarPreferences.deviceProtected(context)
        if (method == METHOD_STATUS) {
            ExperimentSessionRecorder.statusValues(prefs)
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
        if (method == METHOD_RECORD_NOTIFICATION) {
            return recordNotification(prefs, extras)
        }
        if (method == METHOD_RECORD_PUSH_TEST_ARRIVAL) {
            return recordPushTestArrival(prefs, extras)
        }
        if (method == METHOD_SET_GMS_BINDER_ANCHOR_SNAPSHOT) {
            return setGmsBinderAnchorSnapshot(prefs, extras)
        }
        if (
            method == METHOD_START_EXPERIMENT_SESSION ||
            method == METHOD_MARK_EXPERIMENT_SESSION ||
            method == METHOD_STOP_EXPERIMENT_SESSION
        ) {
            val source = extras?.getString(EXTRA_SOURCE).orEmpty()
                .ifBlank { "ui" }
            val operation = when (method) {
                METHOD_START_EXPERIMENT_SESSION ->
                    ExperimentSessionRecorder.start(
                        context,
                        prefs,
                        extras?.getString(EXTRA_SESSION_NAME),
                        source
                    )
                METHOD_MARK_EXPERIMENT_SESSION ->
                    ExperimentSessionRecorder.mark(
                        context,
                        prefs,
                        extras?.getString(EXTRA_MARK_LABEL),
                        source
                    )
                else -> ExperimentSessionRecorder.stop(
                    context,
                    prefs,
                    source
                )
            }
            return experimentResult(operation)
        }
        val ok = when (method) {
            METHOD_SET_NOTIFICATION_LISTENER_STATE -> {
                val connected =
                    extras?.getBoolean(EXTRA_CONNECTED)
                        ?: return result(false)
                prefs.edit()
                    .putBoolean(
                        LuonnotarPreferences
                            .KEY_NOTIFICATION_LISTENER_CONNECTED,
                        connected
                    )
                    .putInt(
                        LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_PID,
                        if (connected) {
                            extras.getInt(EXTRA_PID, 0)
                        } else {
                            0
                        }
                    )
                    .putLong(
                        LuonnotarPreferences
                            .KEY_NOTIFICATION_LISTENER_HEARTBEAT_ELAPSED,
                        if (connected) SystemClock.elapsedRealtime() else 0L
                    )
                    .commit()
            }
            METHOD_SET_GMS_BINDER_ANCHOR_ENABLED -> {
                val enabled = extras?.getBoolean(EXTRA_VALUE)
                    ?: return result(false)
                val editor = prefs.edit()
                    .putBoolean(
                        LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_ENABLED,
                        enabled
                    )
                editor
                    .putString(
                        LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_STATE,
                        if (enabled) {
                            com.yubegreen.luonnotar.notification
                                .GmsBinderAnchorState.WAITING_FOR_GUARDIAN.name
                        } else {
                            com.yubegreen.luonnotar.notification
                                .GmsBinderAnchorState.DISABLED.name
                        }
                    )
                    .putInt(
                        LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_PID,
                        0
                    )
                if (!enabled) {
                    editor
                        .putLong(
                            LuonnotarPreferences
                                .KEY_GMS_BINDER_ANCHOR_CONNECTED_SINCE_ELAPSED,
                            0L
                        )
                        .putInt(
                            LuonnotarPreferences
                                .KEY_GMS_BINDER_ANCHOR_RECONNECT_ATTEMPT,
                            0
                        )
                        .putInt(
                            LuonnotarPreferences
                                .KEY_GMS_BINDER_ANCHOR_SUSPENSION_CAUSE,
                            0
                        )
                        .putInt(
                            LuonnotarPreferences
                                .KEY_GMS_BINDER_ANCHOR_FAILURE_CODE,
                            0
                        )
                        .putInt(
                            LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_PID,
                            0
                        )
                        .putString(
                            LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_BOOT_ID,
                            currentBootId()
                        )
                }
                editor.commit()
            }
            METHOD_REMOVE_NOTIFICATION_KEY -> {
                removeNotificationKey(prefs, extras)
            }
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
                } else if (
                    GuardianProfilePolicy.readProfile(
                        prefs,
                        vivoFamily =
                            GuardianProfilePolicy.isVivoFamily(
                                Build.MANUFACTURER,
                                Build.BRAND
                            )
                    ) == GuardianRuntimeProfile.ADB_PASSIVE
                ) {
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
            METHOD_SET_AGGRESSIVE_MODE -> {
                val enabled = extras?.getBoolean(EXTRA_VALUE) ?: return result(false)
                prefs.edit()
                    .putBoolean(
                        LuonnotarPreferences.KEY_AGGRESSIVE_VIVO_MODE,
                        enabled
                    )
                    .commit()
            }
            METHOD_SET_EXPERIMENT -> {
                val key = extras?.getString(EXTRA_KEY).orEmpty()
                val enabled =
                    extras?.getBoolean(EXTRA_VALUE)
                        ?: return result(false)
                val profile = GuardianProfilePolicy.readProfile(
                    prefs,
                    vivoFamily =
                        GuardianProfilePolicy.isVivoFamily(
                            Build.MANUFACTURER,
                            Build.BRAND
                        )
                )
                val validationFailure =
                    GuardianProfilePolicy.experimentToggleError(
                        profile,
                        key,
                        enabled
                    )
                if (validationFailure != null) {
                    return result(false, validationFailure)
                }
                prefs.edit()
                    .putBoolean(key, enabled)
                    .commit()
            }
            METHOD_SET_PROFILE -> {
                val profile = runCatching {
                    GuardianRuntimeProfile.valueOf(
                        extras?.getString(EXTRA_VALUE).orEmpty()
                    )
                }.getOrNull() ?: return result(false)
                prefs.edit()
                    .putString(
                        LuonnotarPreferences.KEY_GUARDIAN_PROFILE,
                        profile.name
                    )
                    .commit()
            }
            METHOD_SET_LAB_LEVEL -> {
                val level = extras?.getInt(EXTRA_VALUE_INT, -1) ?: -1
                if (level !in 0..4) {
                    false
                } else {
                    prefs.edit()
                        .putInt(
                            LuonnotarPreferences.KEY_LAB_EXTREME_LEVEL,
                            level
                        )
                        .commit()
                    }
            }
            METHOD_SET_RUNTIME_CONFIG -> {
                val config = extras ?: return result(false)
                val profile = runCatching {
                    GuardianRuntimeProfile.valueOf(
                        config.getString(EXTRA_PROFILE).orEmpty()
                    )
                }.getOrNull() ?: return result(false, "invalid_profile")
                val level = config.getInt(EXTRA_VALUE_INT, -1)
                if (level !in 0..4) {
                    return result(false, "invalid_lab_level")
                }

                val persistedExperiments =
                    GuardianProfilePolicy.sanitizeExperiments(
                        profile,
                        GuardianProfilePolicy.experimentKeys.associateWith { key ->
                            prefs.getBoolean(key, false)
                        }
                    )
                val desiredExperiments =
                    GuardianProfilePolicy.experimentKeys.associateWith { key ->
                        if (config.containsKey(key)) {
                            config.getBoolean(key)
                        } else {
                            persistedExperiments[key] == true
                        }
                    }
                val validationFailure =
                    GuardianProfilePolicy.runtimeConfigError(
                        profile,
                        desiredExperiments
                    )
                if (validationFailure != null) {
                    return result(false, validationFailure)
                }

                val editor = prefs.edit()
                    .putString(
                        LuonnotarPreferences.KEY_GUARDIAN_PROFILE,
                        profile.name
                    )
                    .putInt(
                        LuonnotarPreferences.KEY_LAB_EXTREME_LEVEL,
                        level
                    )
                desiredExperiments.forEach { (key, enabled) ->
                    editor.putBoolean(key, enabled)
                }
                editor.commit()
            }
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
                    .remove(
                        LuonnotarPreferences.KEY_ADB_SESSION_FINGERPRINT
                    )
                    .commit()
            }
            else -> false
        }
        return result(ok)
    }

    @Synchronized
    private fun recordNotification(
        prefs: android.content.SharedPreferences,
        extras: Bundle?
    ): Bundle {
        if (
            !prefs.getBoolean(
                LuonnotarPreferences.KEY_NOTIFICATION_PRIVACY_ACK,
                false
            )
        ) {
            return Bundle().apply {
                putBoolean(RESULT_OK, false)
                putString(RESULT_REASON, "privacy_not_acknowledged")
            }
        }
        val packageName = extras?.getString(EXTRA_PACKAGE).orEmpty()
        val keyHash = extras?.getString(EXTRA_KEY_HASH).orEmpty()
        val postTime = extras?.getLong(EXTRA_POST_TIME, -1L) ?: -1L
        if (
            packageName.isBlank() ||
            keyHash.isBlank() ||
            postTime < 0L
        ) return result(false)
        val recent = readRecentFingerprints(
            prefs.getString(
                LuonnotarPreferences.KEY_NOTIFICATION_RECENT_FINGERPRINTS,
                "[]"
            ).orEmpty()
        )
        val decision = NotificationArrivalDeduper.classify(
            recent = recent,
            packageName = packageName,
            keyHash = keyHash,
            postTime = postTime
        )
        if (decision.kind == NotificationArrivalKind.DUPLICATE) {
            return Bundle().apply {
                putBoolean(RESULT_OK, true)
                putString(RESULT_NOTIFICATION_KIND, decision.kind.name)
            }
        }
        val arrivalCount = prefs.getLong(
            LuonnotarPreferences.KEY_NOTIFICATION_COUNT,
            0L
        ) + if (decision.kind == NotificationArrivalKind.NEW) 1L else 0L
        val updateCount = prefs.getLong(
            LuonnotarPreferences.KEY_NOTIFICATION_UPDATE_COUNT,
            0L
        ) + if (decision.kind == NotificationArrivalKind.UPDATE) 1L else 0L
        val seenWall = extras?.getLong(EXTRA_SEEN_WALL, 0L) ?: 0L
        val committed = prefs.edit()
            .putLong(
                LuonnotarPreferences.KEY_NOTIFICATION_COUNT,
                arrivalCount
            )
            .putLong(
                LuonnotarPreferences.KEY_NOTIFICATION_UPDATE_COUNT,
                updateCount
            )
            .putString(
                LuonnotarPreferences.KEY_NOTIFICATION_RECENT_FINGERPRINTS,
                JSONArray(decision.recentFingerprints).toString()
            )
            .putString(
                LuonnotarPreferences.KEY_LAST_NOTIFICATION_PACKAGE,
                packageName
            )
            .putLong(
                LuonnotarPreferences.KEY_LAST_NOTIFICATION_POST_WALL,
                postTime
            )
            .putLong(
                LuonnotarPreferences.KEY_LAST_NOTIFICATION_SEEN_WALL,
                seenWall
            )
            .putString(
                LuonnotarPreferences.KEY_LAST_NOTIFICATION_GROUP_HASH,
                extras?.getString(EXTRA_GROUP_HASH).orEmpty()
            )
            .putBoolean(
                LuonnotarPreferences
                    .KEY_LAST_NOTIFICATION_IS_GROUP_SUMMARY,
                extras?.getBoolean(EXTRA_GROUP_SUMMARY, false) ?: false
            )
            .putLong(
                LuonnotarPreferences
                    .KEY_NOTIFICATION_LISTENER_HEARTBEAT_ELAPSED,
                SystemClock.elapsedRealtime()
            )
            .commit()
        return Bundle().apply {
            putBoolean(RESULT_OK, committed)
            putString(RESULT_NOTIFICATION_KIND, decision.kind.name)
            putLong(RESULT_ARRIVAL_COUNT, arrivalCount)
            putLong(RESULT_UPDATE_COUNT, updateCount)
            putLong(
                RESULT_NETWORK_HANDLE,
                prefs.getLong(
                    LuonnotarPreferences.KEY_NETWORK_HANDLE,
                    -1L
                )
            )
            putLong(
                RESULT_LAST_SUCCESS_ELAPSED,
                prefs.getLong(
                    LuonnotarPreferences.KEY_LAST_SUCCESS_ELAPSED,
                    0L
                )
            )
        }
    }

    @Synchronized
    private fun recordPushTestArrival(
        prefs: android.content.SharedPreferences,
        extras: Bundle?
    ): Bundle {
        if (
            !prefs.getBoolean(
                LuonnotarPreferences.KEY_NOTIFICATION_PRIVACY_ACK,
                false
            )
        ) {
            return Bundle().apply {
                putBoolean(RESULT_OK, false)
                putBoolean(RESULT_ACCEPTED, false)
                putString(RESULT_REASON, "privacy_not_acknowledged")
            }
        }
        val packageName = extras?.getString(EXTRA_PACKAGE).orEmpty()
        val allowedPackage =
            packageName == "com.whatsapp" ||
                packageName == "com.whatsapp.w4b"
        val observationSource =
            extras?.getString(EXTRA_OBSERVATION_SOURCE).orEmpty()
        val liveCallback = observationSource == OBSERVATION_SOURCE_LIVE_CALLBACK
        val activeScan = observationSource == OBSERVATION_SOURCE_ACTIVE_SCAN
        val sequence = extras?.getLong(EXTRA_PUSH_TEST_SEQUENCE, -1L) ?: -1L
        val senderEpochMs =
            extras?.getLong(EXTRA_PUSH_TEST_SENDER_EPOCH_MS, -1L) ?: -1L
        val senderLocalTime =
            extras?.getString(EXTRA_PUSH_TEST_SENDER_LOCAL_TIME).orEmpty()
        val senderZone =
            extras?.getString(EXTRA_PUSH_TEST_SENDER_ZONE).orEmpty()
        val senderPrecisionMs =
            extras?.getLong(EXTRA_PUSH_TEST_SENDER_PRECISION_MS, -1L) ?: -1L
        val seenWall = extras?.getLong(EXTRA_SEEN_WALL, -1L) ?: -1L
        val seenElapsed =
            extras?.getLong(EXTRA_PUSH_TEST_SEEN_ELAPSED, -1L) ?: -1L
        val notificationPostTime =
            extras?.getLong(EXTRA_POST_TIME, -1L) ?: -1L
        if (
            !allowedPackage ||
            (!liveCallback && !activeScan) ||
            sequence <= 0L ||
            senderEpochMs <= 0L ||
            seenWall <= 0L ||
            seenElapsed <= 0L ||
            (activeScan && notificationPostTime <= 0L)
        ) {
            return Bundle().apply {
                putBoolean(RESULT_OK, false)
                putBoolean(RESULT_ACCEPTED, false)
                putString(RESULT_REASON, "invalid_evidence")
            }
        }

        val sequenceKey = if (liveCallback) {
            LuonnotarPreferences.KEY_PUSH_TEST_LAST_SEQUENCE
        } else {
            LuonnotarPreferences.KEY_PUSH_TEST_SCAN_LAST_SEQUENCE
        }
        val senderEpochKey = if (liveCallback) {
            LuonnotarPreferences.KEY_PUSH_TEST_LAST_SENDER_EPOCH_MS
        } else {
            LuonnotarPreferences.KEY_PUSH_TEST_SCAN_LAST_SENDER_EPOCH_MS
        }
        val currentSequence = prefs.getLong(sequenceKey, 0L)
        val currentSenderEpochMs = prefs.getLong(senderEpochKey, 0L)
        val accepted = PushTestDeliveryPolicy.shouldAccept(
            currentSequence = currentSequence,
            currentSenderEpochMs = currentSenderEpochMs,
            candidateSequence = sequence,
            candidateSenderEpochMs = senderEpochMs,
            candidateSeenWall = seenWall
        )
        if (!accepted) {
            return Bundle().apply {
                putBoolean(RESULT_OK, true)
                putBoolean(RESULT_ACCEPTED, false)
                putString(RESULT_REASON, "not_newer_than_persisted_watermark")
                putLong(RESULT_PREVIOUS_PUSH_TEST_SEQUENCE, currentSequence)
            }
        }

        val evidenceUpperBoundWall = if (activeScan) {
            notificationPostTime.coerceAtMost(seenWall)
        } else {
            seenWall
        }
        val delayMs = evidenceUpperBoundWall - senderEpochMs
        val editor = prefs.edit()
        if (liveCallback) {
            editor
                .putLong(
                    LuonnotarPreferences.KEY_PUSH_TEST_LAST_SEQUENCE,
                    sequence
                )
                .putLong(
                    LuonnotarPreferences.KEY_PUSH_TEST_LAST_SENDER_EPOCH_MS,
                    senderEpochMs
                )
                .putString(
                    LuonnotarPreferences.KEY_PUSH_TEST_LAST_SENDER_LOCAL_TIME,
                    senderLocalTime.take(32)
                )
                .putString(
                    LuonnotarPreferences.KEY_PUSH_TEST_LAST_SENDER_ZONE,
                    senderZone.take(48)
                )
                .putLong(
                    LuonnotarPreferences.KEY_PUSH_TEST_LAST_SENDER_PRECISION_MS,
                    senderPrecisionMs.coerceAtLeast(1L)
                )
                .putLong(
                    LuonnotarPreferences.KEY_PUSH_TEST_LAST_SEEN_WALL,
                    seenWall
                )
                .putLong(
                    LuonnotarPreferences.KEY_PUSH_TEST_LAST_SEEN_ELAPSED,
                    seenElapsed
                )
                .putLong(
                    LuonnotarPreferences.KEY_PUSH_TEST_LAST_DELAY_MS,
                    delayMs
                )
                .putString(
                    LuonnotarPreferences.KEY_PUSH_TEST_LAST_PACKAGE,
                    packageName
                )
                .putString(
                    LuonnotarPreferences.KEY_WHATSAPP_DELIVERY_HEALTH,
                    "CONTROLLED_PUSH_TEST_OBSERVED"
                )
        } else {
            editor
                .putLong(
                    LuonnotarPreferences.KEY_PUSH_TEST_SCAN_LAST_SEQUENCE,
                    sequence
                )
                .putLong(
                    LuonnotarPreferences.KEY_PUSH_TEST_SCAN_LAST_SENDER_EPOCH_MS,
                    senderEpochMs
                )
                .putLong(
                    LuonnotarPreferences
                        .KEY_PUSH_TEST_SCAN_LAST_NOTIFICATION_POST_WALL,
                    notificationPostTime
                )
                .putLong(
                    LuonnotarPreferences.KEY_PUSH_TEST_SCAN_LAST_SEEN_WALL,
                    seenWall
                )
                .putLong(
                    LuonnotarPreferences.KEY_PUSH_TEST_SCAN_LAST_SEEN_ELAPSED,
                    seenElapsed
                )
                .putString(
                    LuonnotarPreferences.KEY_PUSH_TEST_SCAN_LAST_PACKAGE,
                    packageName
                )
        }
        val committed = editor.commit()
        return Bundle().apply {
            putBoolean(RESULT_OK, committed)
            putBoolean(RESULT_ACCEPTED, committed)
            putString(RESULT_REASON, if (committed) "" else "commit_failed")
            putLong(RESULT_PREVIOUS_PUSH_TEST_SEQUENCE, currentSequence)
            putLong(RESULT_PUSH_TEST_DELAY_MS, delayMs)
        }
    }

    private fun setGmsBinderAnchorSnapshot(
        prefs: android.content.SharedPreferences,
        extras: Bundle?
    ): Bundle {
        val bundle = extras ?: return result(false)
        val state = bundle.getString(EXTRA_ANCHOR_STATE).orEmpty()
        if (state.isBlank()) return result(false)
        prefs.edit()
            .putString(LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_STATE, state)
            .putLong(
                LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_CONNECTED_SINCE_ELAPSED,
                bundle.getLong(EXTRA_ANCHOR_CONNECTED_SINCE)
            )
            .putLong(
                LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_LAST_EVENT_ELAPSED,
                bundle.getLong(EXTRA_ANCHOR_LAST_EVENT)
            )
            .putInt(
                LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_RECONNECT_ATTEMPT,
                bundle.getInt(EXTRA_ANCHOR_RECONNECT_ATTEMPT)
            )
            .putInt(
                LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_SUSPENSION_CAUSE,
                bundle.getInt(EXTRA_ANCHOR_SUSPENSION_CAUSE)
            )
            .putInt(
                LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_FAILURE_CODE,
                bundle.getInt(EXTRA_ANCHOR_FAILURE_CODE)
            )
            .putLong(
                LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_GMS_VERSION,
                bundle.getLong(EXTRA_ANCHOR_GMS_VERSION)
            )
            .putLong(
                LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_SESSION_GENERATION,
                bundle.getLong(EXTRA_ANCHOR_SESSION_GENERATION)
            )
            .putInt(
                LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_PID,
                bundle.getInt(EXTRA_ANCHOR_PID)
            )
            .putString(
                LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_BOOT_ID,
                bundle.getString(EXTRA_ANCHOR_BOOT_ID).orEmpty()
            )
            .apply()
        return result(true)
    }

    private fun currentBootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("unavailable")

    @Synchronized
    private fun removeNotificationKey(
        prefs: android.content.SharedPreferences,
        extras: Bundle?
    ): Boolean {
        val packageName = extras?.getString(EXTRA_PACKAGE).orEmpty()
        val keyHash = extras?.getString(EXTRA_KEY_HASH).orEmpty()
        if (packageName.isBlank() || keyHash.isBlank()) return false
        val recent = readRecentFingerprints(
            prefs.getString(
                LuonnotarPreferences.KEY_NOTIFICATION_RECENT_FINGERPRINTS,
                "[]"
            ).orEmpty()
        )
        val updated = NotificationArrivalDeduper.removeKey(
            recent,
            packageName,
            keyHash
        )
        if (updated.size == recent.size) return true
        return prefs.edit()
            .putString(
                LuonnotarPreferences.KEY_NOTIFICATION_RECENT_FINGERPRINTS,
                JSONArray(updated).toString()
            )
            .commit()
    }

    private fun readRecentFingerprints(raw: String): List<String> =
        runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index)
                        .takeIf(String::isNotBlank)
                        ?.let(::add)
                }
            }
        }.getOrDefault(emptyList())

    private fun experimentResult(
        operation: ExperimentSessionOperationResult
    ) = Bundle().apply {
        putBoolean(RESULT_OK, operation.ok)
        putString(RESULT_REASON, operation.reason)
        operation.values.forEach { (key, value) ->
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
                else -> Unit
            }
        }
    }

    private fun result(
        ok: Boolean,
        reason: String = ""
    ) = Bundle().apply {
        putBoolean(RESULT_OK, ok)
        putString(RESULT_REASON, reason)
    }

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
        const val METHOD_SET_AGGRESSIVE_MODE = "set_aggressive_mode"
        const val METHOD_SET_EXPERIMENT = "set_guardian_experiment"
        const val METHOD_SET_PROFILE = "set_guardian_profile"
        const val METHOD_SET_LAB_LEVEL = "set_lab_extreme_level"
        const val METHOD_SET_RUNTIME_CONFIG = "set_guardian_runtime_config"
        const val METHOD_REJECT_POLICY = "reject_policy"
        const val METHOD_SET_RECOVERY_FAILURE = "set_recovery_failure"
        const val METHOD_RECORD_BOOT = "record_boot"
        const val METHOD_SCHEDULE_RECOVERY_ALARM = "schedule_recovery_alarm"
        const val METHOD_CLEAR_ADB_EVIDENCE = "clear_adb_evidence"
        const val METHOD_SET_NOTIFICATION_LISTENER_STATE =
            "set_notification_listener_state"
        const val METHOD_RECORD_NOTIFICATION = "record_notification"
        const val METHOD_RECORD_PUSH_TEST_ARRIVAL =
            "record_push_test_arrival"
        const val METHOD_SET_GMS_BINDER_ANCHOR_ENABLED =
            "set_gms_binder_anchor_enabled"
        const val METHOD_SET_GMS_BINDER_ANCHOR_SNAPSHOT =
            "set_gms_binder_anchor_snapshot"
        const val METHOD_REMOVE_NOTIFICATION_KEY =
            "remove_notification_key"
        const val METHOD_START_EXPERIMENT_SESSION =
            "start_experiment_session"
        const val METHOD_MARK_EXPERIMENT_SESSION =
            "mark_experiment_session"
        const val METHOD_STOP_EXPERIMENT_SESSION =
            "stop_experiment_session"
        const val EXTRA_VALUE = "value"
        const val EXTRA_KEY = "key"
        const val EXTRA_VALUE_INT = "value_int"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SESSION_NAME = "session_name"
        const val EXTRA_MARK_LABEL = "mark_label"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_PID = "pid"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_KEY_HASH = "key_hash"
        const val EXTRA_POST_TIME = "post_time"
        const val EXTRA_SEEN_WALL = "seen_wall"
        const val EXTRA_GROUP_HASH = "group_hash"
        const val EXTRA_GROUP_SUMMARY = "group_summary"
        const val EXTRA_PUSH_TEST_SEQUENCE = "push_test_sequence"
        const val EXTRA_PUSH_TEST_SENDER_EPOCH_MS =
            "push_test_sender_epoch_ms"
        const val EXTRA_PUSH_TEST_SENDER_LOCAL_TIME =
            "push_test_sender_local_time"
        const val EXTRA_PUSH_TEST_SENDER_ZONE = "push_test_sender_zone"
        const val EXTRA_PUSH_TEST_SENDER_PRECISION_MS =
            "push_test_sender_precision_ms"
        const val EXTRA_PUSH_TEST_SEEN_ELAPSED =
            "push_test_seen_elapsed"
        const val EXTRA_OBSERVATION_SOURCE = "observation_source"
        const val OBSERVATION_SOURCE_LIVE_CALLBACK = "LIVE_CALLBACK"
        const val OBSERVATION_SOURCE_ACTIVE_SCAN = "ACTIVE_SCAN"
        const val EXTRA_ANCHOR_STATE = "anchor_state"
        const val EXTRA_ANCHOR_CONNECTED_SINCE = "anchor_connected_since"
        const val EXTRA_ANCHOR_LAST_EVENT = "anchor_last_event"
        const val EXTRA_ANCHOR_RECONNECT_ATTEMPT = "anchor_reconnect_attempt"
        const val EXTRA_ANCHOR_SUSPENSION_CAUSE = "anchor_suspension_cause"
        const val EXTRA_ANCHOR_FAILURE_CODE = "anchor_failure_code"
        const val EXTRA_ANCHOR_GMS_VERSION = "anchor_gms_version"
        const val EXTRA_ANCHOR_SESSION_GENERATION = "anchor_session_generation"
        const val EXTRA_ANCHOR_PID = "anchor_pid"
        const val EXTRA_ANCHOR_BOOT_ID = "anchor_boot_id"
        const val SOURCE_SERVICE = "service"
        const val SOURCE_ALARM = "alarm"
        const val SOURCE_NOTIFICATION = "notification"
        const val SOURCE_BOOT = "boot"
        const val RESULT_OK = "ok"
        const val RESULT_REASON = "reason"
        const val RESULT_NOTIFICATION_KIND = "notification_kind"
        const val RESULT_ARRIVAL_COUNT = "arrival_count"
        const val RESULT_UPDATE_COUNT = "update_count"
        const val RESULT_NETWORK_HANDLE = "network_handle"
        const val RESULT_LAST_SUCCESS_ELAPSED =
            "last_success_elapsed"
        const val RESULT_ACCEPTED = "accepted"
        const val RESULT_PREVIOUS_PUSH_TEST_SEQUENCE =
            "previous_push_test_sequence"
        const val RESULT_PUSH_TEST_DELAY_MS = "push_test_delay_ms"
    }
}
