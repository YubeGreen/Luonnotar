package com.yubegreen.luonnotar.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.provider.Settings
import com.yubegreen.luonnotar.ui.i18n.UiText

object NotificationChannelManager {
    const val GUARDIAN_CHANNEL_ID = "luonnotar_guardian"
    const val GUARDIAN_CHANNEL_NAME = "努昂诺塔守护服务"
    const val ALERT_CHANNEL_ID = "luonnotar_alerts"
    const val NOTIFICATION_ID = 1107
    const val ALERT_NOTIFICATION_ID = 1108
    const val RECOVERY_NOTIFICATION_ID = 1109
    const val PRIVILEGED_SETUP_CHANNEL_ID = "luonnotar_privileged_setup"
    const val PRIVILEGED_REBOOT_CHANNEL_ID = "luonnotar_privileged_reboot_v2"
    const val PRIVILEGED_SETUP_NOTIFICATION_ID = 1110
    const val PRIVILEGED_REBOOT_NOTIFICATION_ID = 1111
    const val PRIVILEGED_REBOOT_TEST_NOTIFICATION_ID = 1112

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                GUARDIAN_CHANNEL_ID,
                UiText.choose(context, GUARDIAN_CHANNEL_NAME, "Luonnotar Guardian Service"),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = UiText.choose(
                    context,
                    "显示 Proton VPN / Tailscale 依赖链、唤醒锁与保活证据",
                    "Shows Proton VPN / Tailscale dependency, wake-lock, and keepalive evidence"
                ).toString()
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                PRIVILEGED_SETUP_CHANNEL_ID,
                UiText.choose(context, "努昂诺塔特权引擎启动", "Luonnotar privileged engine startup"),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = UiText.choose(
                    context,
                    "无线调试配对、启动进度与重启后恢复提醒",
                    "Wireless-debugging pairing, startup progress, and post-reboot recovery reminders"
                ).toString()
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                PRIVILEGED_REBOOT_CHANNEL_ID,
                UiText.choose(context, "努昂诺塔特权引擎重启提醒", "Luonnotar privileged-engine reboot reminder"),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = UiText.choose(
                    context,
                    "手机重启后提醒重新启动 shell 特权进程",
                    "Reminds you to restart the shell privileged process after reboot"
                ).toString()
                setShowBadge(false)
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 240L, 140L, 240L)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(
                    Settings.System.DEFAULT_NOTIFICATION_URI,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                UiText.choose(context, "努昂诺塔异常提醒", "Luonnotar alerts"),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = UiText.choose(
                    context,
                    "VPN 丢失或 HTTPS 保活长期失败",
                    "VPN loss or prolonged HTTPS keepalive failures"
                ).toString()
            }
        )
    }
}
