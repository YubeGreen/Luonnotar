package com.yubegreen.luonnotar.receiver

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yubegreen.luonnotar.service.GuardianStatusClient
import com.yubegreen.luonnotar.service.GuardianStatusProvider
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences

class ExactAlarmPermissionChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (
            intent?.action !=
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) return
        val status = GuardianStatusClient.status(context)
        if (status == null) {
            LogManager.event(context, "exact_alarm_permission_status_unavailable")
            return
        }
        val enabled = status.getBoolean(
            LuonnotarPreferences.KEY_ENABLED,
            false
        )
        val paused = status.getBoolean(
            LuonnotarPreferences.KEY_PAUSED,
            false
        )
        if (!enabled || paused) {
            LogManager.event(
                context,
                "exact_alarm_permission_changed_ignored",
                mapOf("enabled" to enabled, "paused" to paused)
            )
            return
        }
        val scheduled = GuardianStatusClient.scheduleRecoveryAlarm(context)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val exactPermission = if (android.os.Build.VERSION.SDK_INT >= 31) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
        val exactRecorded = GuardianStatusClient.status(context)?.getBoolean(
            LuonnotarPreferences.KEY_ALARM_EXACT,
            false
        ) == true
        val insuranceRecorded = GuardianStatusClient.status(context)?.getBoolean(
            LuonnotarPreferences.KEY_ALARM_INSURANCE,
            false
        ) == true
        if (!scheduled || !exactPermission || !exactRecorded || !insuranceRecorded) {
            GuardianStatusClient.setRecoveryFailure(
                context,
                if (scheduled) {
                    "权限变化后精确闹钟或不精确保险仍不可用"
                } else {
                    "精确闹钟权限已变化，但恢复闹钟重排失败"
                },
                GuardianStatusProvider.SOURCE_ALARM
            )
        } else {
            GuardianStatusClient.setRecoveryFailure(
                context,
                "",
                GuardianStatusProvider.SOURCE_ALARM
            )
        }
        LogManager.event(
            context,
            "exact_alarm_permission_changed",
            mapOf(
                "rescheduled" to scheduled,
                "exactPermission" to exactPermission,
                "exactRecorded" to exactRecorded,
                "insuranceRecorded" to insuranceRecorded
            )
        )
    }
}
