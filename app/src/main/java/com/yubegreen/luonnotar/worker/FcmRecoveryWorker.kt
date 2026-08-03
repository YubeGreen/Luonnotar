package com.yubegreen.luonnotar.worker

import android.content.Context
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.yubegreen.luonnotar.service.GuardianLiveness
import com.yubegreen.luonnotar.service.GuardianStatusClient
import com.yubegreen.luonnotar.service.GuardianStatusProvider
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import java.util.concurrent.TimeUnit

class FcmRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        val status = runCatching {
            GuardianStatusClient.status(applicationContext)
        }.onFailure {
            LogManager.event(applicationContext, "recovery_status_read_failed", mapOf("error" to it.toString()))
        }.getOrNull()
            ?: return Result.retry()
        val enabled = status.getBoolean(LuonnotarPreferences.KEY_ENABLED, false)
        val paused = status.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
        if (!enabled || paused) {
            cancelPeriodic(applicationContext)
            LogManager.event(
                applicationContext,
                "recovery_worker_skipped",
                mapOf("enabled" to enabled, "paused" to paused)
            )
            return Result.success()
        }
        val heartbeat = status.getLong(LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED, 0)
        val stale = GuardianLiveness.shouldRecover(
            enabled = enabled,
            nowElapsed = SystemClock.elapsedRealtime(),
            heartbeatElapsed = heartbeat,
            servicePid = status.getInt(LuonnotarPreferences.KEY_PID, 0),
            keeperProcessPid = status.getInt(LuonnotarPreferences.KEY_KEEPER_PROCESS_PID, 0),
            nowUptime = SystemClock.uptimeMillis(),
            lastTickUptime = status.getLong(LuonnotarPreferences.KEY_LAST_TICK_UPTIME, 0L),
            serviceStartedElapsed =
                status.getLong(LuonnotarPreferences.KEY_SERVICE_STARTED_ELAPSED, 0)
        )
        LogManager.event(
            applicationContext,
            "recovery_worker_checked",
            mapOf("heartbeatStale" to stale, "uniqueWork" to UNIQUE_WORK_NAME)
        )
        if (!stale) return Result.success()
        return if (GuardianStatusClient.scheduleRecoveryAlarm(applicationContext)) {
            val exact = GuardianStatusClient.status(applicationContext)
                ?.getBoolean(LuonnotarPreferences.KEY_ALARM_EXACT, false) == true
            GuardianStatusClient.setRecoveryFailure(
                applicationContext,
                if (exact) "" else {
                    "恢复闹钟为不精确；服务被杀后需等待系统触发并点按恢复通知"
                },
                GuardianStatusProvider.SOURCE_ALARM
            )
            LogManager.event(
                applicationContext,
                "recovery_worker_alarm_requested",
                mapOf("mode" to if (exact) "exact" else "inexact_user_interaction")
            )
            Result.success()
        } else {
            GuardianStatusClient.setRecoveryFailure(
                applicationContext,
                "WorkManager: 无法请求 :keeper 恢复闹钟",
                GuardianStatusProvider.SOURCE_ALARM
            )
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "luonnotar_fcm_recovery"
        private const val PERIODIC_UNIQUE_WORK_NAME = "luonnotar_fcm_recovery_periodic"

        fun enqueue(context: Context, reason: String) {
            val request = OneTimeWorkRequestBuilder<FcmRecoveryWorker>()
                .setConstraints(Constraints.Builder().build())
                .addTag(reason)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<FcmRecoveryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().build())
                .addTag(UNIQUE_WORK_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_UNIQUE_WORK_NAME)
        }

        fun cancelAll(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            workManager.cancelUniqueWork(PERIODIC_UNIQUE_WORK_NAME)
        }
    }
}
