package com.yubegreen.luonnotar.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.worker.FcmRecoveryWorker
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GuardianCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        val finished = AtomicBoolean(false)
        val timeout = TIMEOUT_EXECUTOR.schedule({
            if (finished.compareAndSet(false, true)) pending.finish()
        }, FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        runCatching {
            EXECUTOR.execute {
                try {
                    when (intent.action) {
                        ACTION_CLEANUP_DISABLED, ACTION_CANCEL_PAUSED -> {
                            FcmRecoveryWorker.cancelPeriodic(appContext)
                            LogManager.event(
                                appContext,
                                "periodic_recovery_cancelled",
                                mapOf("reason" to intent.action)
                            )
                        }
                        ACTION_ENSURE_ENABLED -> {
                            FcmRecoveryWorker.ensurePeriodic(appContext)
                            LogManager.event(
                                appContext,
                                "periodic_recovery_ensured"
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

    companion object {
        const val ACTION_CLEANUP_DISABLED =
            "com.yubegreen.luonnotar.action.CLEANUP_DISABLED_RECOVERY"
        const val ACTION_CANCEL_PAUSED =
            "com.yubegreen.luonnotar.action.CANCEL_PAUSED_RECOVERY"
        const val ACTION_ENSURE_ENABLED =
            "com.yubegreen.luonnotar.action.ENSURE_ENABLED_RECOVERY"
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
