package com.yubegreen.luonnotar.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yubegreen.luonnotar.privileged.PrivilegedGuardianController
import com.yubegreen.luonnotar.service.GuardianStatusClient
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import com.yubegreen.luonnotar.worker.FcmRecoveryWorker
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GuardianCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        PrivilegedGuardianController.initialize(appContext)
        PrivilegedGuardianController.connectIfEnabled(appContext)
        val finished = AtomicBoolean(false)
        val timeout = TIMEOUT_EXECUTOR.schedule({
            if (finished.compareAndSet(false, true)) pending.finish()
        }, FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        runCatching {
            EXECUTOR.execute {
                try {
                    when (intent.action) {
                        ACTION_CLEANUP_DISABLED, ACTION_CANCEL_PAUSED -> {
                            val status = GuardianStatusClient.status(appContext)
                            if (status == null) {
                                LogManager.event(
                                    appContext,
                                    "cleanup_status_unknown",
                                    mapOf("action" to intent.action)
                                )
                                return@execute
                            }
                            val enabled = status.getBoolean(
                                LuonnotarPreferences.KEY_ENABLED,
                                false
                            )
                            val paused = status.getBoolean(
                                LuonnotarPreferences.KEY_PAUSED,
                                false
                            )
                            val allowed = if (intent.action == ACTION_CLEANUP_DISABLED) {
                                GuardianCleanupPolicy.shouldCancelForDisabled(enabled)
                            } else {
                                GuardianCleanupPolicy.shouldCancelForPaused(enabled, paused)
                            }
                            if (allowed) {
                                FcmRecoveryWorker.cancelPeriodic(appContext)
                                LabAlarmScheduler.cancelAll(appContext)
                                LogManager.event(
                                    appContext,
                                    "periodic_recovery_cancelled",
                                    mapOf("reason" to intent.action)
                                )
                            } else {
                                LogManager.event(
                                    appContext,
                                    if (intent.action == ACTION_CLEANUP_DISABLED) {
                                        "stale_cleanup_ignored"
                                    } else {
                                        "stale_pause_cleanup_ignored"
                                    }
                                )
                            }
                        }
                        ACTION_ENSURE_ENABLED -> {
                            if (!maintenanceIsCurrent(
                                    appContext,
                                    intent
                                )
                            ) return@execute
                            FcmRecoveryWorker.ensurePeriodic(appContext)
                            LogManager.event(
                                appContext,
                                "periodic_recovery_ensured"
                            )
                        }
                        ACTION_ENQUEUE_RECOVERY -> {
                            if (!maintenanceIsCurrent(
                                    appContext,
                                    intent
                                )
                            ) return@execute
                            FcmRecoveryWorker.enqueue(
                                appContext,
                                intent.getStringExtra(EXTRA_REASON)
                                    ?: "main_process_recovery"
                            )
                            LogManager.event(
                                appContext,
                                "one_time_recovery_enqueued"
                            )
                        }
                    }
                } catch (error: Exception) {
                    runCatching {
                        LogManager.event(
                            appContext,
                            "guardian_cleanup_failed",
                            mapOf(
                                "action" to intent.action,
                                "error" to error.toString()
                            )
                        )
                    }
                } finally {
                    if (finished.compareAndSet(false, true)) {
                        timeout.cancel(false)
                        pending.finish()
                    }
                }
            }
        }.onFailure {
            if (finished.compareAndSet(false, true)) {
                timeout.cancel(false)
                pending.finish()
            }
        }
    }

    private fun maintenanceIsCurrent(
        context: Context,
        intent: Intent
    ): Boolean {
        val status = GuardianStatusClient.status(context)
        val expectedGeneration = intent.getLongExtra(
            EXTRA_EXPECTED_GENERATION,
            -1L
        )
        val enabled = status?.getBoolean(
            LuonnotarPreferences.KEY_ENABLED,
            false
        ) == true
        val paused = status?.getBoolean(
            LuonnotarPreferences.KEY_PAUSED,
            true
        ) != false
        val generation = status?.getLong(
            LuonnotarPreferences.KEY_SERVICE_GENERATION,
            -1L
        ) ?: -1L
        val expectedBootId = intent.getStringExtra(
            EXTRA_EXPECTED_BOOT_ID
        ).orEmpty()
        val currentBootId = status?.getString(
            LuonnotarPreferences.KEY_RUNTIME_BOOT_ID,
            ""
        ).orEmpty()
        val bootMatches =
            expectedBootId.isBlank() ||
                (
                    currentBootId.isNotBlank() &&
                        expectedBootId == currentBootId
                    )
        val generationMatches =
            intent.action == ACTION_ENSURE_ENABLED ||
                (
                    expectedGeneration >= 0L &&
                        expectedGeneration == generation
                    )
        val current =
            status != null &&
                enabled &&
                !paused &&
                bootMatches &&
                generationMatches
        if (!current) {
            LogManager.event(
                context,
                "guardian_maintenance_stale_rejected",
                mapOf(
                    "action" to intent.action,
                    "enabled" to enabled,
                    "paused" to paused,
                    "expectedGeneration" to expectedGeneration,
                    "currentGeneration" to generation,
                    "expectedBootId" to expectedBootId,
                    "currentBootId" to currentBootId
                )
            )
        }
        return current
    }

    companion object {
        const val ACTION_CLEANUP_DISABLED =
            "com.yubegreen.luonnotar.action.CLEANUP_DISABLED_RECOVERY"
        const val ACTION_CANCEL_PAUSED =
            "com.yubegreen.luonnotar.action.CANCEL_PAUSED_RECOVERY"
        const val ACTION_ENSURE_ENABLED =
            "com.yubegreen.luonnotar.action.ENSURE_ENABLED_RECOVERY"
        const val ACTION_ENQUEUE_RECOVERY =
            "com.yubegreen.luonnotar.action.ENQUEUE_RECOVERY"
        const val EXTRA_REASON = "recovery_reason"
        const val EXTRA_EXPECTED_GENERATION =
            "expected_service_generation"
        const val EXTRA_EXPECTED_BOOT_ID = "expected_boot_id"
        private const val FINISH_TIMEOUT_SECONDS = 8L
        private val EXECUTOR = Executors.newSingleThreadExecutor {
            Thread(it, "luonnotar-cleanup-worker").apply { isDaemon = true }
        }
        private val TIMEOUT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor {
                Thread(it, "luonnotar-cleanup-timeout").apply { isDaemon = true }
            }
    }
}
