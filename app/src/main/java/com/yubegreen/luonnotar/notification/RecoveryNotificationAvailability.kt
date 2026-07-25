package com.yubegreen.luonnotar.notification

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

data class RecoveryNotificationState(
    val available: Boolean,
    val explanation: String
)

object RecoveryNotificationAvailability {
    fun evaluate(context: Context): RecoveryNotificationState {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return RecoveryNotificationState(
                false,
                "恢复入口不可用：通知运行时权限未授予"
            )
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return RecoveryNotificationState(
                false,
                "恢复入口不可用：努昂诺塔通知已被全局关闭"
            )
        }
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(NotificationChannelManager.ALERT_CHANNEL_ID)
        if (channel == null) {
            return RecoveryNotificationState(
                false,
                "恢复入口不可用：异常通知渠道尚未创建"
            )
        }
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
            return RecoveryNotificationState(
                false,
                "恢复入口不可用：异常通知渠道已关闭"
            )
        }
        return RecoveryNotificationState(true, "异常恢复通知入口可见")
    }

    fun evaluateGuardian(context: Context): RecoveryNotificationState {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return RecoveryNotificationState(false, "前台守护通知不可见：通知权限未授予")
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return RecoveryNotificationState(false, "前台守护通知不可见：应用通知已全局关闭")
        }
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(NotificationChannelManager.GUARDIAN_CHANNEL_ID)
        return when {
            channel == null ->
                RecoveryNotificationState(false, "前台守护通知渠道尚未创建")
            channel.importance == NotificationManager.IMPORTANCE_NONE ->
                RecoveryNotificationState(false, "前台守护通知渠道已关闭")
            else -> RecoveryNotificationState(true, "前台守护通知可见")
        }
    }
}
