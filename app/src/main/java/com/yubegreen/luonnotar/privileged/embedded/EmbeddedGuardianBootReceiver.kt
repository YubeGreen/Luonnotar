package com.yubegreen.luonnotar.privileged.embedded

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yubegreen.luonnotar.notification.NotificationChannelManager
import com.yubegreen.luonnotar.util.LogManager

class EmbeddedGuardianBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action.orEmpty()
        if (action !in ACTIONS) return
        NotificationChannelManager.create(context)
        val snapshot = EmbeddedGuardianStore.snapshot(context)
        if (!snapshot.featureEnabled) return
        val pending = EmbeddedGuardianStore.rebootReminder(context)

        // A shell app_process cannot survive a device reboot or APK replacement. Persist the
        // reminder in device-protected storage and post it immediately, including during Direct
        // Boot. BOOT_COMPLETED and USER_UNLOCKED may arrive after the user has already restarted
        // the engine, so they must not invalidate a newly verified UID 2000 handshake.
        val mustInvalidateRuntime =
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                (!pending.pending && !snapshot.liveConnected)
        if (mustInvalidateRuntime) {
            EmbeddedGuardianStore.markRuntimeUnavailableAfterBoot(
                context = context,
                source = "boot_receiver",
                bootAction = action
            )
        }
        EmbeddedGuardianNotifier.reconcileRebootReminder(
            context = context,
            source = "boot_receiver",
            fallbackBootAction = action
        )
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching {
                EmbeddedGuardianManager.startSetup(context, "package_replaced_auto_repair")
            }.onFailure { error ->
                LogManager.event(
                    context,
                    "embedded_package_replace_auto_repair_failed",
                    mapOf("error" to error.toString())
                )
            }
        }
    }

    companion object {
        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
