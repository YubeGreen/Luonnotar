package com.yubegreen.luonnotar.privileged.embedded

import com.yubegreen.luonnotar.ActionActivity
import com.yubegreen.luonnotar.notification.NotificationChannelManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class EmbeddedGuardianNotificationPolicyTest {

    @Test fun rebootReminderUsesDedicatedFreshAlertChannel() {
        assertEquals(
            "luonnotar_privileged_reboot_v2",
            NotificationChannelManager.PRIVILEGED_REBOOT_CHANNEL_ID
        )
        assertFalse(
            NotificationChannelManager.PRIVILEGED_REBOOT_CHANNEL_ID ==
                NotificationChannelManager.PRIVILEGED_SETUP_CHANNEL_ID
        )
    }

    @Test fun rebootAlertTestUsesASeparateNotificationId() {
        assertNotEquals(
            NotificationChannelManager.PRIVILEGED_REBOOT_NOTIFICATION_ID,
            NotificationChannelManager.PRIVILEGED_REBOOT_TEST_NOTIFICATION_ID
        )
    }

    @Test fun rebootReminderContentUsesMainActivityPendingIntent() {
        val spec = EmbeddedGuardianNotificationPolicy.rebootContentSpec
        assertEquals(EmbeddedGuardianNotificationPolicy.PendingIntentKind.ACTIVITY, spec.kind)
        assertEquals(EmbeddedGuardianNotificationPolicy.Destination.MAIN_ACTIVITY, spec.destination)
    }

    @Test fun rebootStartUsesActionActivityAndCorrectAction() {
        val spec = EmbeddedGuardianNotificationPolicy.rebootStartSpec
        assertEquals(EmbeddedGuardianNotificationPolicy.PendingIntentKind.ACTIVITY, spec.kind)
        assertEquals(EmbeddedGuardianNotificationPolicy.Destination.ACTION_ACTIVITY, spec.destination)
        assertEquals(ActionActivity.ACTION_START_EMBEDDED_GUARDIAN, spec.action)
        assertTrue(EmbeddedGuardianNotificationPolicy.shouldStartSetup(spec.action))
    }

    @Test fun notificationRequestCodesDoNotCollide() {
        val codes = listOf(
            EmbeddedGuardianNotificationPolicy.PAIRING_REQUEST_CODE,
            EmbeddedGuardianNotificationPolicy.RETRY_REQUEST_CODE,
            EmbeddedGuardianNotificationPolicy.WIRELESS_REQUEST_CODE,
            EmbeddedGuardianNotificationPolicy.SETUP_CONTENT_REQUEST_CODE,
            EmbeddedGuardianNotificationPolicy.REBOOT_CONTENT_REQUEST_CODE,
            EmbeddedGuardianNotificationPolicy.REBOOT_START_REQUEST_CODE
        )
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test fun startActionHasWirelessDeveloperAndMainFallbackRoute() {
        assertEquals(
            listOf(
                "android.settings.WIRELESS_DEBUGGING_SETTINGS",
                "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"
            ),
            EmbeddedGuardianNotificationPolicy.wirelessSettingsActions
        )
        assertTrue(EmbeddedGuardianNotificationPolicy.fallsBackToMainActivity())
    }

    @Test fun actionActivityStartPlanInvokesSetupThenWirelessSettings() {
        val calls = mutableListOf<String>()
        assertTrue(
            EmbeddedGuardianNotificationPolicy.executeStartAction(
                ActionActivity.ACTION_START_EMBEDDED_GUARDIAN,
                startSetup = { calls += "setup" },
                onStartFailure = { calls += "failure" },
                openWirelessSettings = { calls += "wireless" }
            )
        )
        assertEquals(listOf("setup", "wireless"), calls)
    }

    @Test fun setupFailureStillOpensWirelessFallbackFlow() {
        val calls = mutableListOf<String>()
        assertTrue(
            EmbeddedGuardianNotificationPolicy.executeStartAction(
                ActionActivity.ACTION_START_EMBEDDED_GUARDIAN,
                startSetup = { error("blocked") },
                onStartFailure = { calls += "failure" },
                openWirelessSettings = { calls += "wireless" }
            )
        )
        assertEquals(listOf("failure", "wireless"), calls)
        assertFalse(
            EmbeddedGuardianNotificationPolicy.executeStartAction(
                "unrelated",
                startSetup = { calls += "unexpected" },
                onStartFailure = { calls += "unexpected" },
                openWirelessSettings = { calls += "unexpected" }
            )
        )
    }
}
