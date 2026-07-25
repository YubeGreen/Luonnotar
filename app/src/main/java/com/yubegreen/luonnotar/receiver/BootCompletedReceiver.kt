package com.yubegreen.luonnotar.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.yubegreen.luonnotar.service.FcmGuardianService
import com.yubegreen.luonnotar.service.GuardianStatusClient
import com.yubegreen.luonnotar.service.GuardianStatusProvider
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import com.yubegreen.luonnotar.worker.FcmRecoveryWorker

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACCEPTED_ACTIONS) return
        GuardianStatusClient.recordBootAction(context, action)
        LogManager.event(context, "boot_or_update_broadcast", mapOf("action" to action))
        val status = GuardianStatusClient.status(context)
        val enabled = status?.getBoolean(LuonnotarPreferences.KEY_ENABLED, false)
        val paused = status?.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
        if (enabled == false || paused == true) {
            if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                runCatching { FcmRecoveryWorker.cancelPeriodic(context) }
            }
            return
        }
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FcmGuardianService::class.java)
                    .setAction(
                        if (status == null) FcmGuardianService.ACTION_RECOVER
                        else FcmGuardianService.ACTION_START
                    )
                    .putExtra(
                        FcmGuardianService.EXTRA_START_REASON,
                        if (status == null) "${action}_status_unavailable" else action
                    )
            )
        } catch (error: Exception) {
            GuardianStatusClient.setRecoveryFailure(
                context,
                "BootReceiver: ${error.javaClass.simpleName}: ${error.message}",
                GuardianStatusProvider.SOURCE_BOOT
            )
            LogManager.event(context, "boot_service_start_blocked", mapOf("error" to error.toString()))
            if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                runCatching { FcmRecoveryWorker.enqueue(context, "boot_fallback") }
                    .onFailure {
                        LogManager.event(
                            context,
                            "boot_work_fallback_failed",
                            mapOf("error" to it.toString())
                        )
                    }
            }
        }
        GuardianStatusClient.scheduleRecoveryAlarm(context)
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            runCatching { FcmRecoveryWorker.ensurePeriodic(context) }
                .onFailure {
                    LogManager.event(context, "periodic_recovery_schedule_failed", mapOf("error" to it.toString()))
                }
        }
    }

    companion object {
        private val ACCEPTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
