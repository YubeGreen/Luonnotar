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

    fun setExperiment(
        context: Context,
        key: String,
        enabled: Boolean
    ): Boolean =
        call(
            context,
            GuardianStatusProvider.METHOD_SET_EXPERIMENT,
            Bundle().apply {
                putString(GuardianStatusProvider.EXTRA_KEY, key)
                putBoolean(GuardianStatusProvider.EXTRA_VALUE, enabled)
            }
        )?.getBoolean(GuardianStatusProvider.RESULT_OK) == true

    fun setProfile(
        context: Context,
        profile: GuardianRuntimeProfile
    ): Boolean =
        call(
            context,
            GuardianStatusProvider.METHOD_SET_PROFILE,
            Bundle().apply {
                putString(GuardianStatusProvider.EXTRA_VALUE, profile.name)
            }
        )?.getBoolean(GuardianStatusProvider.RESULT_OK) == true

    fun setLabLevel(context: Context, level: Int): Boolean =
        call(
            context,
            GuardianStatusProvider.METHOD_SET_LAB_LEVEL,
            Bundle().apply {
                putInt(GuardianStatusProvider.EXTRA_VALUE_INT, level)
            }
        )?.getBoolean(GuardianStatusProvider.RESULT_OK) == true

    fun setRuntimeConfig(
        context: Context,
        profile: GuardianRuntimeProfile,
        level: Int,
        experiments: Map<String, Boolean>
    ): Boolean =
        call(
            context,
            GuardianStatusProvider.METHOD_SET_RUNTIME_CONFIG,
            Bundle().apply {
                putString(
                    GuardianStatusProvider.EXTRA_PROFILE,
                    profile.name
                )
                putInt(
                    GuardianStatusProvider.EXTRA_VALUE_INT,
                    level
                )
                experiments.forEach { (key, value) ->
                    if (key in GuardianProfilePolicy.experimentKeys) {
                        putBoolean(key, value)
                    }
                }
            }
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

    fun setNotificationListenerState(
        context: Context,
        connected: Boolean,
        pid: Int
    ): Boolean =
        call(
            context,
            GuardianStatusProvider.METHOD_SET_NOTIFICATION_LISTENER_STATE,
            Bundle().apply {
                putBoolean(
                    GuardianStatusProvider.EXTRA_CONNECTED,
                    connected
                )
                putInt(GuardianStatusProvider.EXTRA_PID, pid)
            }
        )?.getBoolean(GuardianStatusProvider.RESULT_OK) == true

    fun setGmsBinderAnchorEnabled(context: Context, enabled: Boolean): Boolean =
        call(
            context,
            GuardianStatusProvider.METHOD_SET_GMS_BINDER_ANCHOR_ENABLED,
            Bundle().apply {
                putBoolean(GuardianStatusProvider.EXTRA_VALUE, enabled)
            }
        )?.getBoolean(GuardianStatusProvider.RESULT_OK) == true

    fun setGmsBinderAnchorSnapshot(
        context: Context,
        snapshot: com.yubegreen.luonnotar.notification.GmsBinderAnchorSnapshot
    ): Boolean = call(
        context,
        GuardianStatusProvider.METHOD_SET_GMS_BINDER_ANCHOR_SNAPSHOT,
        Bundle().apply {
            putString(
                GuardianStatusProvider.EXTRA_ANCHOR_STATE,
                snapshot.state.name
            )
            putLong(
                GuardianStatusProvider.EXTRA_ANCHOR_CONNECTED_SINCE,
                snapshot.connectedSinceElapsed
            )
            putLong(
                GuardianStatusProvider.EXTRA_ANCHOR_LAST_EVENT,
                snapshot.lastEventElapsed
            )
            putInt(
                GuardianStatusProvider.EXTRA_ANCHOR_RECONNECT_ATTEMPT,
                snapshot.reconnectAttempt
            )
            putInt(
                GuardianStatusProvider.EXTRA_ANCHOR_SUSPENSION_CAUSE,
                snapshot.suspensionCause
            )
            putInt(
                GuardianStatusProvider.EXTRA_ANCHOR_FAILURE_CODE,
                snapshot.failureCode
            )
            putLong(
                GuardianStatusProvider.EXTRA_ANCHOR_GMS_VERSION,
                snapshot.gmsVersionCode
            )
            putLong(
                GuardianStatusProvider.EXTRA_ANCHOR_SESSION_GENERATION,
                snapshot.sessionGeneration
            )
            putInt(
                GuardianStatusProvider.EXTRA_ANCHOR_PID,
                snapshot.hostPid
            )
            putString(
                GuardianStatusProvider.EXTRA_ANCHOR_BOOT_ID,
                snapshot.bootId
            )
        }
    )?.getBoolean(GuardianStatusProvider.RESULT_OK) == true

    fun recordNotification(
        context: Context,
        packageName: String,
        keyHash: String,
        postTime: Long,
        seenWall: Long,
        groupHash: String,
        groupSummary: Boolean
    ): Bundle? =
        call(
            context,
            GuardianStatusProvider.METHOD_RECORD_NOTIFICATION,
            Bundle().apply {
                putString(
                    GuardianStatusProvider.EXTRA_PACKAGE,
                    packageName
                )
                putString(
                    GuardianStatusProvider.EXTRA_KEY_HASH,
                    keyHash
                )
                putLong(
                    GuardianStatusProvider.EXTRA_POST_TIME,
                    postTime
                )
                putLong(
                    GuardianStatusProvider.EXTRA_SEEN_WALL,
                    seenWall
                )
                putString(
                    GuardianStatusProvider.EXTRA_GROUP_HASH,
                    groupHash
                )
                putBoolean(
                    GuardianStatusProvider.EXTRA_GROUP_SUMMARY,
                    groupSummary
                )
            }
        )

    fun recordPushTestArrival(
        context: Context,
        packageName: String,
        pushTest: com.yubegreen.luonnotar.notification.PushTestNotification,
        seenWall: Long,
        seenElapsed: Long,
        postTime: Long,
        observationSource: String
    ): Bundle? =
        call(
            context,
            GuardianStatusProvider.METHOD_RECORD_PUSH_TEST_ARRIVAL,
            Bundle().apply {
                putString(GuardianStatusProvider.EXTRA_PACKAGE, packageName)
                putLong(
                    GuardianStatusProvider.EXTRA_PUSH_TEST_SEQUENCE,
                    pushTest.sequence
                )
                putLong(
                    GuardianStatusProvider.EXTRA_PUSH_TEST_SENDER_EPOCH_MS,
                    pushTest.senderEpochMs
                )
                putString(
                    GuardianStatusProvider.EXTRA_PUSH_TEST_SENDER_LOCAL_TIME,
                    pushTest.senderLocalTime
                )
                putString(
                    GuardianStatusProvider.EXTRA_PUSH_TEST_SENDER_ZONE,
                    pushTest.senderZoneId
                )
                putLong(
                    GuardianStatusProvider.EXTRA_PUSH_TEST_SENDER_PRECISION_MS,
                    pushTest.senderPrecisionMs
                )
                putLong(GuardianStatusProvider.EXTRA_SEEN_WALL, seenWall)
                putLong(
                    GuardianStatusProvider.EXTRA_PUSH_TEST_SEEN_ELAPSED,
                    seenElapsed
                )
                putLong(GuardianStatusProvider.EXTRA_POST_TIME, postTime)
                putString(
                    GuardianStatusProvider.EXTRA_OBSERVATION_SOURCE,
                    observationSource
                )
            }
        )

    fun startExperimentSession(
        context: Context,
        name: String,
        source: String = "ui"
    ): Bundle? =
        call(
            context,
            GuardianStatusProvider.METHOD_START_EXPERIMENT_SESSION,
            Bundle().apply {
                putString(GuardianStatusProvider.EXTRA_SESSION_NAME, name)
                putString(GuardianStatusProvider.EXTRA_SOURCE, source)
            }
        )

    fun markExperimentSession(
        context: Context,
        label: String,
        source: String = "ui"
    ): Bundle? =
        call(
            context,
            GuardianStatusProvider.METHOD_MARK_EXPERIMENT_SESSION,
            Bundle().apply {
                putString(GuardianStatusProvider.EXTRA_MARK_LABEL, label)
                putString(GuardianStatusProvider.EXTRA_SOURCE, source)
            }
        )

    fun stopExperimentSession(
        context: Context,
        source: String = "ui"
    ): Bundle? =
        call(
            context,
            GuardianStatusProvider.METHOD_STOP_EXPERIMENT_SESSION,
            Bundle().apply {
                putString(GuardianStatusProvider.EXTRA_SOURCE, source)
            }
        )

    fun removeNotificationKey(
        context: Context,
        packageName: String,
        keyHash: String
    ): Boolean =
        call(
            context,
            GuardianStatusProvider.METHOD_REMOVE_NOTIFICATION_KEY,
            Bundle().apply {
                putString(
                    GuardianStatusProvider.EXTRA_PACKAGE,
                    packageName
                )
                putString(
                    GuardianStatusProvider.EXTRA_KEY_HASH,
                    keyHash
                )
            }
        )?.getBoolean(GuardianStatusProvider.RESULT_OK) == true

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
