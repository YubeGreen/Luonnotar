package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedRebootAlertGuidePolicyTest {
    @Test fun android13RequiresRuntimeNotificationPermission() {
        assertTrue(
            EmbeddedRebootAlertGuidePolicy.requiresRuntimePermission(
                sdkInt = 33,
                runtimePermissionGranted = false
            )
        )
        assertFalse(
            EmbeddedRebootAlertGuidePolicy.requiresRuntimePermission(
                sdkInt = 33,
                runtimePermissionGranted = true
            )
        )
    }

    @Test fun preAndroid13DoesNotRequestRuntimeNotificationPermission() {
        assertFalse(
            EmbeddedRebootAlertGuidePolicy.requiresRuntimePermission(
                sdkInt = 32,
                runtimePermissionGranted = false
            )
        )
    }

    @Test fun guideVisitsSettingsUntilNotificationsChannelAndVisitAreReady() {
        assertTrue(
            EmbeddedRebootAlertGuidePolicy.shouldRunGuide(
                sdkInt = 36,
                runtimePermissionGranted = true,
                notificationsEnabled = true,
                channelEnabled = true,
                settingsVisited = false
            )
        )
        assertTrue(
            EmbeddedRebootAlertGuidePolicy.shouldRunGuide(
                sdkInt = 36,
                runtimePermissionGranted = true,
                notificationsEnabled = false,
                channelEnabled = true,
                settingsVisited = true
            )
        )
        assertTrue(
            EmbeddedRebootAlertGuidePolicy.shouldRunGuide(
                sdkInt = 36,
                runtimePermissionGranted = true,
                notificationsEnabled = true,
                channelEnabled = false,
                settingsVisited = true
            )
        )
        assertFalse(
            EmbeddedRebootAlertGuidePolicy.shouldRunGuide(
                sdkInt = 36,
                runtimePermissionGranted = true,
                notificationsEnabled = true,
                channelEnabled = true,
                settingsVisited = true
            )
        )
    }

    @Test fun recentSettingsReturnTriggersOneTestNotification() {
        assertTrue(
            EmbeddedRebootAlertGuidePolicy.shouldSendTestAfterSettingsReturn(
                settingsOpenedElapsed = 1_000L,
                nowElapsed = 1_000L + 30_000L
            )
        )
    }

    @Test fun staleOrInvalidSettingsReturnDoesNotTriggerTest() {
        assertFalse(
            EmbeddedRebootAlertGuidePolicy.shouldSendTestAfterSettingsReturn(
                settingsOpenedElapsed = 0L,
                nowElapsed = 30_000L
            )
        )
        assertFalse(
            EmbeddedRebootAlertGuidePolicy.shouldSendTestAfterSettingsReturn(
                settingsOpenedElapsed = 1_000L,
                nowElapsed = 1_000L + EmbeddedRebootAlertGuidePolicy.SETTINGS_RETURN_MAX_AGE_MS + 1L
            )
        )
        assertFalse(
            EmbeddedRebootAlertGuidePolicy.shouldSendTestAfterSettingsReturn(
                settingsOpenedElapsed = 5_000L,
                nowElapsed = 4_999L
            )
        )
    }
}
