package com.yubegreen.luonnotar.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.worker.FcmRecoveryWorker

class GuardianCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                runCatching {
                    when (intent.action) {
                        ACTION_CLEANUP_DISABLED, ACTION_CANCEL_PAUSED -> {
                            FcmRecoveryWorker.cancelPeriodic(context)
                            LogManager.event(
                                context,
                                "periodic_recovery_cancelled",
                                mapOf("reason" to intent.action)
                            )
                        }
                        ACTION_ENSURE_ENABLED -> {
                            FcmRecoveryWorker.ensurePeriodic(context)
                            LogManager.event(context, "periodic_recovery_ensured")
                        }
                    }
                }.onFailure {
                    LogManager.event(
                        context,
                        "guardian_cleanup_failed",
                        mapOf("action" to intent.action, "error" to it.toString())
                    )
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_CLEANUP_DISABLED =
            "com.yubegreen.luonnotar.action.CLEANUP_DISABLED_RECOVERY"
        const val ACTION_CANCEL_PAUSED =
            "com.yubegreen.luonnotar.action.CANCEL_PAUSED_RECOVERY"
        const val ACTION_ENSURE_ENABLED =
            "com.yubegreen.luonnotar.action.ENSURE_ENABLED_RECOVERY"
    }
}
