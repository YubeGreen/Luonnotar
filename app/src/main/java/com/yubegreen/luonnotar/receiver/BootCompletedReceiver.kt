package com.yubegreen.luonnotar.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.yubegreen.luonnotar.service.FcmGuardianService
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACCEPTED_ACTIONS) return
        val appContext = context.applicationContext
        val preferences = LuonnotarPreferences.deviceProtected(appContext)
        val enabled = preferences.getBoolean(
            LuonnotarPreferences.KEY_ENABLED,
            false
        )
        val paused = preferences.getBoolean(
            LuonnotarPreferences.KEY_PAUSED,
            false
        )
        val requestedElapsed = SystemClock.elapsedRealtime()
        val bootId = preferences.getString(
            LuonnotarPreferences.KEY_RUNTIME_BOOT_ID,
            ""
        ).orEmpty()
        val decision = if (enabled && !paused) {
            BootRecoveryDedupPolicy.decide(
                action = action,
                currentBootId = bootId,
                nowElapsed = requestedElapsed,
                lastBootId = preferences.getString(
                    LuonnotarPreferences.KEY_BOOT_RECOVERY_CLAIM_BOOT_ID,
                    ""
                ),
                lastAcceptedElapsed = preferences.getLong(
                    LuonnotarPreferences.KEY_BOOT_RECOVERY_CLAIM_ELAPSED,
                    0L
                ),
                lastDispatchAccepted = preferences.getBoolean(
                    LuonnotarPreferences.KEY_BOOT_RECOVERY_DISPATCH_ACCEPTED,
                    false
                )
            )
        } else {
            BootRecoveryDecision(
                deduplicated = false,
                reason = if (!enabled) "guardian_disabled" else "guardian_paused",
                deltaElapsed = -1L
            )
        }
        val shouldStartService = enabled && !paused && !decision.deduplicated
        val claimEditor = preferences.edit()
            .putString(LuonnotarPreferences.KEY_LAST_BOOT_BROADCAST, action)
        if (
            shouldStartService &&
            BootRecoveryDedupPolicy.isClusteredBootAction(action)
        ) {
            claimEditor
                .putString(
                    LuonnotarPreferences.KEY_BOOT_RECOVERY_CLAIM_BOOT_ID,
                    bootId
                )
                .putString(
                    LuonnotarPreferences.KEY_BOOT_RECOVERY_CLAIM_ACTION,
                    action
                )
                .putLong(
                    LuonnotarPreferences.KEY_BOOT_RECOVERY_CLAIM_ELAPSED,
                    requestedElapsed
                )
                .putBoolean(
                    LuonnotarPreferences.KEY_BOOT_RECOVERY_DISPATCH_ACCEPTED,
                    true
                )
                .putBoolean(
                    LuonnotarPreferences.KEY_RECOVERY_CONFIRMATION_PENDING,
                    true
                )
                .putLong(
                    LuonnotarPreferences.KEY_RECOVERY_REQUESTED_ELAPSED,
                    requestedElapsed
                )
                .putString(
                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_BOOT,
                    "已收到系统启动事件；等待新的守护心跳与 VPN 路径证据"
                )
                .putLong(
                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_BOOT_ELAPSED,
                    requestedElapsed
                )
        }
        val claimCommitted = claimEditor.commit()
        var serviceStartError: Throwable? = null
        if (shouldStartService) {
            runCatching {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, FcmGuardianService::class.java)
                        .setAction(FcmGuardianService.ACTION_START)
                        .putExtra(FcmGuardianService.EXTRA_START_REASON, action)
                )
            }.onFailure { error ->
                serviceStartError = error
                preferences.edit()
                    .putBoolean(
                        LuonnotarPreferences.KEY_BOOT_RECOVERY_DISPATCH_ACCEPTED,
                        false
                    )
                    .putString(
                        LuonnotarPreferences.KEY_RECOVERY_FAILURE_BOOT,
                        "BootReceiver: ${error.javaClass.simpleName}: ${error.message}"
                    )
                    .putLong(
                        LuonnotarPreferences.KEY_RECOVERY_FAILURE_BOOT_ELAPSED,
                        SystemClock.elapsedRealtime()
                    )
                    .commit()
            }
        }
        dispatchDeferredWork(
            appContext = appContext,
            action = action,
            enabled = enabled,
            paused = paused,
            shouldStartService = shouldStartService,
            serviceStartError = serviceStartError,
            decision = decision,
            bootId = bootId,
            claimCommitted = claimCommitted,
            requestedElapsed = requestedElapsed
        )
    }

    private fun dispatchDeferredWork(
        appContext: Context,
        action: String,
        enabled: Boolean,
        paused: Boolean,
        shouldStartService: Boolean,
        serviceStartError: Throwable?,
        decision: BootRecoveryDecision,
        bootId: String,
        claimCommitted: Boolean,
        requestedElapsed: Long
    ) {
        val pendingResult = goAsync()
        val finished = AtomicBoolean(false)
        val timeout = ASYNC_TIMEOUT_EXECUTOR.schedule({
            if (finished.compareAndSet(false, true)) {
                pendingResult.finish()
            }
        }, ASYNC_FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        runCatching {
            ASYNC_EXECUTOR.execute {
                try {
                    LogManager.event(
                        appContext,
                        "boot_recovery_broadcast",
                        mapOf(
                            "action" to action,
                            "bootIdAnonymous" to
                                BootRecoveryDedupPolicy.anonymousBootId(bootId),
                            "deduplicatedServiceStart" to decision.deduplicated,
                            "reason" to decision.reason,
                            "deltaElapsedMs" to decision.deltaElapsed,
                            "claimCommitted" to claimCommitted,
                            "serviceStartRequested" to shouldStartService,
                            "serviceStartAccepted" to
                                (shouldStartService && serviceStartError == null)
                        )
                    )
                    if (!enabled || paused) {
                        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                            sendMainProcessMaintenance(
                                appContext,
                                GuardianCleanupReceiver.ACTION_CLEANUP_DISABLED,
                                action
                            )
                        }
                        return@execute
                    }
                    if (serviceStartError != null) {
                        LogManager.event(
                            appContext,
                            "boot_service_start_blocked",
                            mapOf("error" to serviceStartError.toString())
                        )
                        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                            sendMainProcessMaintenance(
                                appContext,
                                GuardianCleanupReceiver.ACTION_ENQUEUE_RECOVERY,
                                "boot_fallback"
                            )
                        }
                    }
                    if (!decision.deduplicated) {
                        if (!LabAlarmScheduler.scheduleNext(appContext)) {
                            LuonnotarPreferences.deviceProtected(appContext)
                                .edit()
                                .putString(
                                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM,
                                    "启动恢复期间无法安排下一次守护自检"
                                )
                                .putLong(
                                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM_ELAPSED,
                                    SystemClock.elapsedRealtime()
                                )
                                .apply()
                        }
                    }
                    if (BootRecoveryDedupPolicy.requiresUnlockedMaintenance(action)) {
                        sendMainProcessMaintenance(
                            appContext,
                            GuardianCleanupReceiver.ACTION_ENSURE_ENABLED,
                            action
                        )
                    }
                    LogManager.event(
                        appContext,
                        "boot_recovery_deferred_work_finished",
                        mapOf(
                            "action" to action,
                            "elapsedMs" to
                                (SystemClock.elapsedRealtime() - requestedElapsed),
                            "periodicEnsured" to
                                BootRecoveryDedupPolicy.requiresUnlockedMaintenance(
                                    action
                                )
                        )
                    )
                } finally {
                    if (finished.compareAndSet(false, true)) {
                        timeout.cancel(false)
                        pendingResult.finish()
                    }
                }
            }
        }.onFailure { error ->
            LogManager.event(
                appContext,
                "boot_recovery_async_submit_failed",
                mapOf("error" to error.toString())
            )
            if (finished.compareAndSet(false, true)) {
                timeout.cancel(false)
                pendingResult.finish()
            }
        }
    }

    private fun sendMainProcessMaintenance(
        context: Context,
        action: String,
        reason: String
    ) {
        runCatching {
            context.sendBroadcast(
                Intent(context, GuardianCleanupReceiver::class.java)
                    .setAction(action)
                    .putExtra(GuardianCleanupReceiver.EXTRA_REASON, reason)
                    .putExtra(
                        GuardianCleanupReceiver.EXTRA_EXPECTED_GENERATION,
                        LuonnotarPreferences.deviceProtected(context)
                            .getLong(
                                LuonnotarPreferences.KEY_SERVICE_GENERATION,
                                -1L
                            )
                    )
                    .putExtra(
                        GuardianCleanupReceiver.EXTRA_EXPECTED_BOOT_ID,
                        LuonnotarPreferences.deviceProtected(context)
                            .getString(
                                LuonnotarPreferences.KEY_RUNTIME_BOOT_ID,
                                ""
                            )
                    )
            )
        }.onFailure {
            LogManager.event(
                context,
                "main_process_recovery_dispatch_failed",
                mapOf(
                    "action" to action,
                    "reason" to reason,
                    "error" to it.toString()
                )
            )
        }
    }

    companion object {
        private const val ASYNC_FINISH_TIMEOUT_SECONDS = 8L
        private val ACCEPTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
        private val ASYNC_EXECUTOR = Executors.newSingleThreadExecutor {
            Thread(it, "luonnotar-boot-worker").apply { isDaemon = true }
        }
        private val ASYNC_TIMEOUT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor {
                Thread(it, "luonnotar-boot-timeout").apply { isDaemon = true }
            }
    }
}
