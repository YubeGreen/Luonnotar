package com.yubegreen.luonnotar.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationChannelManager {
    const val GUARDIAN_CHANNEL_ID = "luonnotar_guardian"
    const val GUARDIAN_CHANNEL_NAME = "努昂诺塔守护服务"
    const val ALERT_CHANNEL_ID = "luonnotar_alerts"
    const val NOTIFICATION_ID = 1107
    const val ALERT_NOTIFICATION_ID = 1108
    const val RECOVERY_NOTIFICATION_ID = 1109

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                GUARDIAN_CHANNEL_ID,
                GUARDIAN_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示 Proton VPN / Tailscale 依赖链、唤醒锁与保活证据"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                "努昂诺塔异常提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "VPN 丢失或 HTTPS 保活长期失败"
            }
        )
    }
}
