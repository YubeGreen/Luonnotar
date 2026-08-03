package com.yubegreen.luonnotar.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yubegreen.luonnotar.ActionActivity
import com.yubegreen.luonnotar.R
import com.yubegreen.luonnotar.notification.NotificationChannelManager
import com.yubegreen.luonnotar.notification.RecoveryNotificationAvailability
import com.yubegreen.luonnotar.service.FcmGuardianService
import com.yubegreen.luonnotar.service.GuardianLiveness
import com.yubegreen.luonnotar.service.GuardianStatusProvider
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences

class LabAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val fallback = LuonnotarPreferences.deviceProtected(context)
            val status = runCatching {
                context.contentResolver.call(
                    Uri.parse("content://${context.packageName}.status"),
                    GuardianStatusProvider.METHOD_STATUS,
                    null,
                    null
                )
            }.onFailure {
                LogManager.event(context, "lab_alarm_status_read_failed", mapOf("error" to it.toString()))
            }.getOrNull()
            val enabled = fallback.getBoolean(LuonnotarPreferences.KEY_ENABLED, false)
            val paused = fallback.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
            if (!enabled) return
            val last = status?.getLong(
                LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED,
                fallback.getLong(LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED, 0)
            ) ?: fallback.getLong(LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED, 0)
            val stale = GuardianLiveness.shouldRecover(
                enabled = enabled,
                nowElapsed = SystemClock.elapsedRealtime(),
                heartbeatElapsed = last,
                servicePid = status?.getInt(
                    LuonnotarPreferences.KEY_PID,
                    fallback.getInt(LuonnotarPreferences.KEY_PID, 0)
                ) ?: fallback.getInt(LuonnotarPreferences.KEY_PID, 0),
                keeperProcessPid = status?.getInt(
                    LuonnotarPreferences.KEY_KEEPER_PROCESS_PID,
                    0
                ) ?: 0,
                nowUptime = SystemClock.uptimeMillis(),
                lastTickUptime = status?.getLong(
                    LuonnotarPreferences.KEY_LAST_TICK_UPTIME,
                    fallback.getLong(LuonnotarPreferences.KEY_LAST_TICK_UPTIME, 0L)
                ) ?: fallback.getLong(LuonnotarPreferences.KEY_LAST_TICK_UPTIME, 0L),
                serviceStartedElapsed = status?.getLong(
                    LuonnotarPreferences.KEY_SERVICE_STARTED_ELAPSED,
                    fallback.getLong(
                        LuonnotarPreferences.KEY_SERVICE_STARTED_ELAPSED,
                        0L
                    )
                ) ?: 0L
            )
            val exactEligible =
                intent?.getBooleanExtra(LabAlarmScheduler.EXTRA_EXACT_ELIGIBLE, false) == true
            val hardRestart =
                intent?.getBooleanExtra(
                    LabAlarmScheduler.EXTRA_HARD_RESTART,
                    false
                ) == true
            if (hardRestart) {
                val nonce = intent?.getStringExtra(
                    LabAlarmScheduler.EXTRA_RESTART_NONCE
                ).orEmpty()
                val expectedOldPid = intent?.getIntExtra(
                    LabAlarmScheduler.EXTRA_EXPECTED_OLD_PID,
                    -1
                ) ?: -1
                val expectedGeneration = intent?.getLongExtra(
                    LabAlarmScheduler.EXTRA_EXPECTED_GENERATION,
                    -1L
                ) ?: -1L
                val expectedPermitOwner = intent?.getLongExtra(
                    LabAlarmScheduler.EXTRA_EXPECTED_PERMIT_OWNER,
                    -1L
                ) ?: -1L
                val metadataMatches =
                    nonce.isNotBlank() &&
                        fallback.getString(
                            LuonnotarPreferences.KEY_HARD_RESTART_NONCE,
                            ""
                        ) == nonce &&
                        fallback.getInt(
                            LuonnotarPreferences
                                .KEY_HARD_RESTART_EXPECTED_PID,
                            -1
                        ) == expectedOldPid &&
                        fallback.getLong(
                            LuonnotarPreferences
                                .KEY_HARD_RESTART_EXPECTED_GENERATION,
                            -1L
                        ) == expectedGeneration &&
                        fallback.getLong(
                            LuonnotarPreferences
                                .KEY_HARD_RESTART_EXPECTED_PERMIT_OWNER,
                            -1L
                        ) == expectedPermitOwner
                when (
                    HardRestartDispatchPolicy.decide(
                        metadataMatches,
                        Process.myPid(),
                        expectedOldPid
                    )
                ) {
                    HardRestartDispatchAction.REJECT -> {
                    LogManager.event(
                        context,
                        "hard_restart_alarm_rejected",
                        mapOf("reason" to "nonce_or_generation_mismatch")
                    )
                    LabAlarmScheduler.cancelHardRestart(context)
                    return
                    }
                    HardRestartDispatchAction.RESCHEDULE_OLD_PID -> {
                    val rescheduled =
                        LabAlarmScheduler.scheduleHardRestart(
                            context,
                            nonce,
                            expectedOldPid,
                            expectedGeneration,
                            expectedPermitOwner
                        )
                    LogManager.event(
                        context,
                        "hard_restart_alarm_old_pid_still_alive",
                        mapOf(
                            "oldPid" to expectedOldPid,
                            "rescheduled" to rescheduled
                        )
                    )
                    if (!rescheduled) {
                        fallback.edit()
                            .putString(
                                LuonnotarPreferences
                                    .KEY_RECOVERY_FAILURE_SERVICE,
                                "Keeper 硬恢复已停止：旧进程仍存活且近端重试已达上限"
                            )
                            .putLong(
                                LuonnotarPreferences
                                    .KEY_RECOVERY_FAILURE_SERVICE_ELAPSED,
                                SystemClock.elapsedRealtime()
                            )
                            .apply()
                        LabAlarmScheduler.cancelHardRestart(context)
                    }
                    return
                    }
                    HardRestartDispatchAction.START_NEW_KEEPER -> Unit
                }
                LabAlarmScheduler.cancelHardRestart(context)
            }
            val action = if (hardRestart && enabled && !paused) {
                AlarmRecoveryPolicy.Action.START_FOREGROUND_SERVICE
            } else {
                AlarmRecoveryPolicy.decide(
                    enabled = enabled,
                    paused = paused,
                    heartbeatStale = stale,
                    sdkInt = Build.VERSION.SDK_INT,
                    exactAlarmEligible = exactEligible
                )
            }
            LogManager.event(
                context,
                "lab_alarm_fired",
                mapOf(
                    "heartbeatStale" to stale,
                    "paused" to paused,
                    "exactEligible" to exactEligible,
                    "hardRestart" to hardRestart,
                    "action" to action.name
                )
            )
            when (action) {
                AlarmRecoveryPolicy.Action.START_FOREGROUND_SERVICE -> {
                    runCatching {
                        ContextCompat.startForegroundService(
                            context,
                            guardianStartIntent(
                                context,
                                if (hardRestart) {
                                    "hard_probe_restart_alarm"
                                } else {
                                    "exact_lab_alarm"
                                }
                            )
                        )
                    }.onFailure {
                        val message = "系统阻止后台恢复：${it.javaClass.simpleName}"
                        fallback.edit()
                            .putString(
                                LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE,
                                message
                            )
                            .putLong(
                                LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE_ELAPSED,
                                SystemClock.elapsedRealtime()
                            )
                            .apply()
                        showUserRecoveryNotification(context, message)
                        LogManager.event(
                            context,
                            "lab_alarm_start_blocked",
                            mapOf("error" to it.toString())
                        )
                    }
                }
                AlarmRecoveryPolicy.Action.REQUIRE_USER_INTERACTION -> {
                    val notificationState =
                        RecoveryNotificationAvailability.evaluate(context)
                    val message = if (notificationState.available) {
                        "当前是不精确闹钟；Android 12+ 需要点按通知才能恢复守护服务"
                    } else {
                        notificationState.explanation
                    }
                    fallback.edit()
                        .putString(
                            LuonnotarPreferences.KEY_RECOVERY_FAILURE_NOTIFICATION,
                            message
                        )
                        .putLong(
                            LuonnotarPreferences.KEY_RECOVERY_FAILURE_NOTIFICATION_ELAPSED,
                            SystemClock.elapsedRealtime()
                        )
                        .apply()
                    if (notificationState.available) {
                        showUserRecoveryNotification(context, message)
                    }
                    LogManager.event(context, "lab_alarm_user_interaction_required")
                }
                AlarmRecoveryPolicy.Action.NONE -> Unit
            }
        } finally {
            val preferences = LuonnotarPreferences.deviceProtected(context)
            if (
                preferences.getBoolean(LuonnotarPreferences.KEY_ENABLED, false) &&
                !preferences.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
            ) {
                val scheduled = LabAlarmScheduler.scheduleNext(context)
                if (!scheduled) {
                    val message = "恢复链中断：下一次自检闹钟安排失败"
                    preferences.edit()
                        .putString(
                            LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM,
                            message
                        )
                        .putLong(
                            LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM_ELAPSED,
                            SystemClock.elapsedRealtime()
                        )
                        .apply()
                    LogManager.event(
                        context,
                        "lab_alarm_reschedule_failed",
                        mapOf("error" to message)
                    )
                }
            } else {
                LabAlarmScheduler.cancel(context)
            }
        }
    }

    private fun showUserRecoveryNotification(context: Context, detail: String) {
        NotificationChannelManager.create(context)
        val availability = RecoveryNotificationAvailability.evaluate(context)
        if (!availability.available) {
            LuonnotarPreferences.deviceProtected(context).edit()
                .putString(
                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_NOTIFICATION,
                    availability.explanation
                )
                .putLong(
                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_NOTIFICATION_ELAPSED,
                    SystemClock.elapsedRealtime()
                )
                .apply()
            LogManager.event(
                context,
                "recovery_notification_unavailable",
                mapOf("reason" to availability.explanation)
            )
            return
        }
        val userRecovery = PendingIntent.getActivity(
            context,
            NotificationChannelManager.RECOVERY_NOTIFICATION_ID,
            Intent(context, ActionActivity::class.java)
                .setAction(ActionActivity.ACTION_RECOVER_GUARDIAN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(
            context,
            NotificationChannelManager.ALERT_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle("努昂诺塔需要手动恢复")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(userRecovery)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .addAction(0, "立即恢复", userRecovery)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(NotificationChannelManager.RECOVERY_NOTIFICATION_ID, notification)
    }

    private fun guardianStartIntent(context: Context, reason: String) =
        Intent(context, FcmGuardianService::class.java)
            .setAction(FcmGuardianService.ACTION_RECOVER)
            .putExtra(FcmGuardianService.EXTRA_START_REASON, reason)
}
