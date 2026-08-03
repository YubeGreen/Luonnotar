package com.yubegreen.luonnotar.privileged.embedded

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.yubegreen.luonnotar.ActionActivity
import com.yubegreen.luonnotar.MainActivity
import com.yubegreen.luonnotar.R
import com.yubegreen.luonnotar.notification.NotificationChannelManager
import com.yubegreen.luonnotar.util.LogManager

internal object EmbeddedGuardianNotifier {
    const val REMOTE_INPUT_KEY = "embedded_adb_pairing_code"

    fun setupNotification(context: Context, title: String, text: String, waitingCode: Boolean) =
        NotificationCompat.Builder(context, NotificationChannelManager.PRIVILEGED_SETUP_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_luonnotar)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(mainPendingIntent(context))
            .addAction(openWirelessAction(context))
            .apply { if (waitingCode) addAction(pairingCodeAction(context)) }
            .addAction(retryAction(context))
            .build()

    fun showRebootReminder(
        context: Context,
        reason: String = "手机重启后需要重新启动 shell 特权进程",
        source: String = "boot_receiver",
        bootAction: String = ""
    ) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(
            context,
            NotificationChannelManager.PRIVILEGED_REBOOT_CHANNEL_ID
        )
            .setSmallIcon(R.mipmap.ic_luonnotar)
            .setContentTitle("努昂诺塔特权引擎尚未启动")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$reason。点击继续，开启无线调试后可在通知栏输入配对码。"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setContentIntent(rebootContentPendingIntent(context, source, bootAction))
            .addAction(
                NotificationCompat.Action(
                    0,
                    "去启动",
                    rebootStartPendingIntent(context, source, bootAction)
                )
            )
            .build()
        val manager = context.getSystemService(NotificationManager::class.java)
        removeLegacyGroupedReminder(manager)
        manager.notify(NotificationChannelManager.PRIVILEGED_REBOOT_NOTIFICATION_ID, notification)
        val channel = manager.getNotificationChannel(
            NotificationChannelManager.PRIVILEGED_REBOOT_CHANNEL_ID
        )
        LogManager.event(
            context,
            "embedded_reboot_reminder_posted",
            eventFields(context, source, bootAction) + mapOf(
                "channelId" to NotificationChannelManager.PRIVILEGED_REBOOT_CHANNEL_ID,
                "channelImportance" to channel?.importance,
                "channelSound" to channel?.sound?.toString(),
                "channelVibration" to channel?.shouldVibrate(),
                "notificationOngoing" to true,
                "notificationCategory" to NotificationCompat.CATEGORY_REMINDER
            )
        )
    }

    fun reconcileRebootReminder(
        context: Context,
        source: String,
        fallbackBootAction: String = ""
    ) {
        NotificationChannelManager.create(context)
        val snapshot = EmbeddedGuardianStore.snapshot(context)
        val pending = EmbeddedGuardianStore.rebootReminder(context)
        when (
            EmbeddedRebootReminderPolicy.decide(
                featureEnabled = snapshot.featureEnabled,
                liveConnected = snapshot.liveConnected,
                pending = pending.pending
            )
        ) {
            EmbeddedRebootReminderPolicy.Action.POST -> showRebootReminder(
                context = context,
                source = source,
                bootAction = pending.bootAction.ifBlank { fallbackBootAction }
            )

            EmbeddedRebootReminderPolicy.Action.CANCEL -> cancelRebootReminder(context)
            EmbeddedRebootReminderPolicy.Action.NONE -> Unit
        }
        LogManager.event(
            context,
            "embedded_reboot_reminder_reconciled",
            eventFields(
                context,
                source = source,
                bootAction = pending.bootAction.ifBlank { fallbackBootAction }
            ) + mapOf(
                "pending" to pending.pending,
                "pendingSource" to pending.source,
                "pendingCreatedWall" to pending.createdWall
            )
        )
    }

    fun showRebootAlertTest(
        context: Context,
        source: String = "main_activity"
    ): Boolean {
        if (!canNotify(context) || !isUserUnlocked(context)) return false
        NotificationChannelManager.create(context)
        val notification = NotificationCompat.Builder(
            context,
            NotificationChannelManager.PRIVILEGED_REBOOT_CHANNEL_ID
        )
            .setSmallIcon(R.mipmap.ic_luonnotar)
            .setContentTitle("努昂诺塔重启横幅测试")
            .setContentText("看到这条悬浮横幅，说明重启提醒配置成功")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "看到这条悬浮横幅，说明“特权引擎重启提醒”的悬浮通知已经开启。"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setTimeoutAfter(20_000L)
            .setContentIntent(mainPendingIntent(context))
            .build()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(
            NotificationChannelManager.PRIVILEGED_REBOOT_TEST_NOTIFICATION_ID,
            notification
        )
        val channel = manager.getNotificationChannel(
            NotificationChannelManager.PRIVILEGED_REBOOT_CHANNEL_ID
        )
        LogManager.event(
            context,
            "embedded_reboot_alert_test_posted",
            eventFields(context, source, "") + mapOf(
                "channelId" to NotificationChannelManager.PRIVILEGED_REBOOT_CHANNEL_ID,
                "channelImportance" to channel?.importance,
                "channelSound" to channel?.sound?.toString(),
                "channelVibration" to channel?.shouldVibrate()
            )
        )
        return true
    }

    fun cancelRebootReminder(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(NotificationChannelManager.PRIVILEGED_REBOOT_NOTIFICATION_ID)
    }

    fun cancelAll(context: Context) {
        context.getSystemService(NotificationManager::class.java).apply {
            cancel(NotificationChannelManager.PRIVILEGED_SETUP_NOTIFICATION_ID)
            cancel(NotificationChannelManager.PRIVILEGED_REBOOT_NOTIFICATION_ID)
            cancel(NotificationChannelManager.PRIVILEGED_REBOOT_TEST_NOTIFICATION_ID)
        }
    }

    private fun pairingCodeAction(context: Context): NotificationCompat.Action {
        val input = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel("输入系统显示的 6 位配对码")
            .build()
        return NotificationCompat.Action.Builder(
            0,
            "输入配对码",
            servicePendingIntent(
                context,
                EmbeddedAdbService.ACTION_PAIR,
                EmbeddedGuardianNotificationPolicy.PAIRING_REQUEST_CODE,
                mutable = true
            )
        )
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .build()
    }

    private fun retryAction(context: Context) = NotificationCompat.Action(
        0,
        "重新检测",
        servicePendingIntent(
            context,
            EmbeddedAdbService.ACTION_RETRY,
            EmbeddedGuardianNotificationPolicy.RETRY_REQUEST_CODE
        )
    )

    private fun openWirelessAction(context: Context) = NotificationCompat.Action(
        0,
        "无线调试",
        PendingIntent.getActivity(
            context,
            EmbeddedGuardianNotificationPolicy.WIRELESS_REQUEST_CODE,
            Intent(context, ActionActivity::class.java)
                .setAction(ActionActivity.ACTION_OPEN_WIRELESS_DEBUGGING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    )

    private fun rebootContentPendingIntent(
        context: Context,
        source: String,
        bootAction: String
    ): PendingIntent = PendingIntent.getActivity(
        context,
        EmbeddedGuardianNotificationPolicy.rebootContentSpec.requestCode,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_SCROLL_TO_EMBEDDED_GUARDIAN, true)
            .putExtra(MainActivity.EXTRA_EMBEDDED_NOTIFICATION_SOURCE, source)
            .putExtra(MainActivity.EXTRA_EMBEDDED_NOTIFICATION_BOOT_ACTION, bootAction),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun rebootStartPendingIntent(
        context: Context,
        source: String,
        bootAction: String
    ): PendingIntent = PendingIntent.getActivity(
        context,
        EmbeddedGuardianNotificationPolicy.rebootStartSpec.requestCode,
        Intent(context, ActionActivity::class.java)
            .setAction(EmbeddedGuardianNotificationPolicy.rebootStartSpec.action)
            .putExtra(ActionActivity.EXTRA_EMBEDDED_NOTIFICATION_SOURCE, source)
            .putExtra(ActionActivity.EXTRA_EMBEDDED_NOTIFICATION_BOOT_ACTION, bootAction),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun mainPendingIntent(context: Context): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            EmbeddedGuardianNotificationPolicy.SETUP_CONTENT_REQUEST_CODE,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun servicePendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
        mutable: Boolean = false
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getForegroundService(
            context,
            requestCode,
            Intent(context, EmbeddedAdbService::class.java).setAction(action),
            flags
        )
    }

    private fun removeLegacyGroupedReminder(manager: NotificationManager) {
        val existing = runCatching {
            manager.activeNotifications.firstOrNull {
                it.id == NotificationChannelManager.PRIVILEGED_REBOOT_NOTIFICATION_ID
            }
        }.getOrNull()
        if (existing?.notification?.group != null) {
            manager.cancel(NotificationChannelManager.PRIVILEGED_REBOOT_NOTIFICATION_ID)
        }
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    internal fun eventFields(
        context: Context,
        source: String,
        bootAction: String
    ): Map<String, Any?> {
        val snapshot = EmbeddedGuardianStore.snapshot(context)
        val pending = EmbeddedGuardianStore.rebootReminder(context)
        return mapOf(
            "source" to source,
            "bootAction" to bootAction,
            "userUnlocked" to isUserUnlocked(context),
            "featureEnabled" to snapshot.featureEnabled,
            "setupState" to snapshot.setupState.name,
            "connectionState" to snapshot.connectionState.name,
            "binderAlive" to snapshot.binderAlive,
            "reportedUid" to snapshot.reportedUid,
            "engineUpdatedElapsed" to snapshot.updatedElapsed,
            "rebootReminderPending" to pending.pending,
            "rebootReminderBootAction" to pending.bootAction
        )
    }

    private fun isUserUnlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
}
