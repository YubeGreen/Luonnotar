package com.yubegreen.luonnotar.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences

object LabAlarmScheduler {
    private const val REQUEST_CODE_EXACT = 1107
    private const val REQUEST_CODE_INEXACT = 1108
    internal const val MIN_IDLE_INTERVAL_MS = 9 * 60_000L
    internal const val INSURANCE_INTERVAL_MS = 45 * 60_000L
    internal const val EXTRA_EXACT_ELIGIBLE = "exact_eligible"
    private const val ACTION_LAB_ALARM_EXACT =
        "com.yubegreen.luonnotar.action.LAB_ALARM_EXACT"
    private const val ACTION_LAB_ALARM_INEXACT =
        "com.yubegreen.luonnotar.action.LAB_ALARM_INEXACT"

    @android.annotation.SuppressLint("ScheduleExactAlarm")
    @Synchronized
    fun scheduleNext(context: Context): Boolean {
        val preferences = LuonnotarPreferences.deviceProtected(context)
        if (
            !preferences.getBoolean(LuonnotarPreferences.KEY_ENABLED, false) ||
            preferences.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
        ) {
            cancel(context)
            return false
        }
        cancel(context)
        val manager = context.getSystemService(AlarmManager::class.java)
        val exactAllowed = isExactAlarmAllowed(manager)
        val exactPending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_EXACT,
            alarmIntent(context, exactEligible = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val scheduledElapsed = SystemClock.elapsedRealtime()
        val trigger = scheduledElapsed + MIN_IDLE_INTERVAL_MS
        val exactScheduled = exactAllowed && runCatching {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                trigger,
                exactPending
            )
        }.onFailure {
            LogManager.event(
                context,
                "lab_alarm_exact_schedule_failed",
                mapOf("error" to it.toString())
            )
        }.isSuccess
        val fallbackPending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_INEXACT,
            alarmIntent(context, exactEligible = false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val fallbackTrigger = scheduledElapsed +
            if (exactScheduled) INSURANCE_INTERVAL_MS else MIN_IDLE_INTERVAL_MS
        val fallbackScheduled = runCatching {
            manager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                fallbackTrigger,
                fallbackPending
            )
        }.onFailure {
            LogManager.event(
                context,
                "lab_alarm_inexact_schedule_failed",
                mapOf("error" to it.toString())
            )
        }.isSuccess
        if (!exactScheduled && !fallbackScheduled) return false
        LogManager.event(
            context,
            "lab_alarm_scheduled",
            mapOf(
                "mode" to if (exactScheduled) "exact_plus_inexact_insurance"
                    else "inexact_fallback",
                "insurance" to fallbackScheduled
            )
        )
        val metadataEditor = preferences.edit()
            .putBoolean(LuonnotarPreferences.KEY_ALARM_EXACT, exactScheduled)
            .putBoolean(LuonnotarPreferences.KEY_ALARM_INSURANCE, fallbackScheduled)
            .putLong(LuonnotarPreferences.KEY_ALARM_SCHEDULED_ELAPSED, scheduledElapsed)
        when {
            exactScheduled && fallbackScheduled ->
                metadataEditor
                    .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM)
                    .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM_ELAPSED)
            exactScheduled ->
                metadataEditor
                    .putString(
                        LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM,
                        "精确闹钟可用，但独立不精确保险安排失败"
                    )
                    .putLong(
                        LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM_ELAPSED,
                        scheduledElapsed
                    )
            else ->
                metadataEditor
                    .putString(
                        LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM,
                        "精确闹钟不可用；当前仅有不精确恢复保险"
                    )
                    .putLong(
                        LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM_ELAPSED,
                        scheduledElapsed
                    )
        }
        val metadataCommitted = metadataEditor.commit()
        if (!metadataCommitted) {
            manager.cancel(exactPending)
            exactPending.cancel()
            manager.cancel(fallbackPending)
            fallbackPending.cancel()
            LogManager.event(context, "lab_alarm_metadata_commit_failed")
        }
        return metadataCommitted
    }

    @Synchronized
    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        listOf(
            REQUEST_CODE_EXACT to true,
            REQUEST_CODE_INEXACT to false
        ).forEach { (requestCode, exact) ->
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode,
                alarmIntent(context, exact),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pending != null) {
                manager.cancel(pending)
                pending.cancel()
            }
        }
        LuonnotarPreferences.deviceProtected(context).edit()
            .remove(LuonnotarPreferences.KEY_ALARM_EXACT)
            .remove(LuonnotarPreferences.KEY_ALARM_INSURANCE)
            .remove(LuonnotarPreferences.KEY_ALARM_SCHEDULED_ELAPSED)
            .apply()
    }

    internal fun isExactAlarmAllowed(manager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    private fun alarmIntent(context: Context, exactEligible: Boolean) =
        Intent(
            if (exactEligible) ACTION_LAB_ALARM_EXACT else ACTION_LAB_ALARM_INEXACT
        )
            .setComponent(
                ComponentName(
                    context.packageName,
                    "com.yubegreen.luonnotar.receiver.LabAlarmReceiver"
                )
            )
            .putExtra(EXTRA_EXACT_ELIGIBLE, exactEligible)
}
