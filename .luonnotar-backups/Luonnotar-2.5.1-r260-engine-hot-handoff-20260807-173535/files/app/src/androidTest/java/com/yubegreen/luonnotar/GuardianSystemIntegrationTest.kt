package com.yubegreen.luonnotar

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.SystemClock
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yubegreen.luonnotar.receiver.BootCompletedReceiver
import com.yubegreen.luonnotar.receiver.AdbFreezerDiagnosticsReceiver
import com.yubegreen.luonnotar.receiver.AdbGmsBinderPulseReceiver
import com.yubegreen.luonnotar.receiver.AdbNotificationDiagnosticsReceiver
import com.yubegreen.luonnotar.receiver.AdbRuntimeConfigReceiver
import com.yubegreen.luonnotar.receiver.AdbVpnVerificationReceiver
import com.yubegreen.luonnotar.receiver.ExactAlarmPermissionChangedReceiver
import com.yubegreen.luonnotar.receiver.GuardianCleanupReceiver
import com.yubegreen.luonnotar.receiver.LabAlarmScheduler
import com.yubegreen.luonnotar.notification.ArrivalNotificationListener
import com.yubegreen.luonnotar.notification.NotificationChannelManager
import com.yubegreen.luonnotar.monitor.TargetUidHealthSnapshot
import com.yubegreen.luonnotar.monitor.DiagnosticTruth
import com.yubegreen.luonnotar.service.AdbRuntimeConfigProvider
import com.yubegreen.luonnotar.service.FcmGuardianService
import com.yubegreen.luonnotar.service.GuardianStatusClient
import com.yubegreen.luonnotar.service.GuardianStatusProvider
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import com.yubegreen.luonnotar.util.LogManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class GuardianSystemIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun targetUidHealthSnapshotSanitizedArrayRoundTrips() {
        val original = TargetUidHealthSnapshot(
            packageName = "com.google.android.gms",
            uid = 10171,
            installed = DiagnosticTruth.TRUE,
            frozen = DiagnosticTruth.FALSE,
            processPresent = DiagnosticTruth.TRUE,
            processState = "IMPB",
            backgroundRestricted = DiagnosticTruth.FALSE,
            standbyBucket = "5",
            inactive = DiagnosticTruth.FALSE,
            netpolicyBlocked = DiagnosticTruth.FALSE,
            packageStopped = DiagnosticTruth.FALSE,
            packageEnabled = DiagnosticTruth.TRUE,
            packageSuspended = DiagnosticTruth.FALSE,
            notificationEnabled = DiagnosticTruth.FALSE,
            postNotificationsAllowed = DiagnosticTruth.FALSE,
            capturedWallTime = "2026-07-26T09:24:00+12:00",
            commandSupported = DiagnosticTruth.TRUE,
            exitCode = 0,
            outputParsed = DiagnosticTruth.TRUE,
            captureError = ""
        )

        val sanitized =
            TargetUidHealthSnapshot.toSanitizedArray(listOf(original))

        assertEquals(
            listOf(original),
            TargetUidHealthSnapshot.parseArray(sanitized)
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun sensitiveComponentsArePrivateAndKeeperIsIsolated() {
        val boot = context.packageManager.getReceiverInfo(
            ComponentName(context, BootCompletedReceiver::class.java),
            PackageManager.GET_META_DATA
        )
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, FcmGuardianService::class.java),
            PackageManager.GET_META_DATA
        )
        val provider = context.packageManager.getProviderInfo(
            ComponentName(context, GuardianStatusProvider::class.java),
            PackageManager.GET_META_DATA
        )
        val adbRuntimeProvider = context.packageManager.getProviderInfo(
            ComponentName(context, AdbRuntimeConfigProvider::class.java),
            PackageManager.GET_META_DATA
        )
        val exactAlarmPermissionReceiver = context.packageManager.getReceiverInfo(
            ComponentName(context, ExactAlarmPermissionChangedReceiver::class.java),
            PackageManager.GET_META_DATA
        )
        val cleanup = context.packageManager.getReceiverInfo(
            ComponentName(context, GuardianCleanupReceiver::class.java),
            PackageManager.GET_META_DATA
        )
        val adbVpn = context.packageManager.getReceiverInfo(
            ComponentName(context, AdbVpnVerificationReceiver::class.java),
            PackageManager.GET_META_DATA
        )
        val adbFreezer = context.packageManager.getReceiverInfo(
            ComponentName(context, AdbFreezerDiagnosticsReceiver::class.java),
            PackageManager.GET_META_DATA
        )
        val adbNotification = context.packageManager.getReceiverInfo(
            ComponentName(context, AdbNotificationDiagnosticsReceiver::class.java),
            PackageManager.GET_META_DATA
        )
        val adbGmsBinderPulse = context.packageManager.getReceiverInfo(
            ComponentName(context, AdbGmsBinderPulseReceiver::class.java),
            PackageManager.GET_META_DATA
        )
        val adbRuntimeConfig = context.packageManager.getReceiverInfo(
            ComponentName(context, AdbRuntimeConfigReceiver::class.java),
            PackageManager.GET_META_DATA
        )
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES
        )
        val notificationListener = packageInfo.services.orEmpty()
            .first { it.name == ArrivalNotificationListener::class.java.name }

        assertFalse(boot.exported)
        assertFalse(exactAlarmPermissionReceiver.exported)
        assertTrue(boot.directBootAware)
        assertFalse(service.exported)
        assertTrue(service.directBootAware)
        assertFalse(provider.exported)
        assertTrue(provider.directBootAware)
        assertTrue(adbRuntimeProvider.exported)
        assertTrue(adbRuntimeProvider.directBootAware)
        assertTrue(adbRuntimeProvider.permission == "android.permission.DUMP")
        assertTrue(boot.processName.endsWith(":keeper"))
        assertTrue(service.processName.endsWith(":keeper"))
        assertTrue(provider.processName.endsWith(":keeper"))
        assertTrue(adbRuntimeProvider.processName == service.processName)
        assertFalse(notificationListener.processName == service.processName)
        assertFalse(cleanup.processName.endsWith(":keeper"))
        assertTrue(adbVpn.exported)
        assertTrue(adbFreezer.exported)
        assertTrue(adbNotification.exported)
        assertTrue(adbGmsBinderPulse.exported)
        assertTrue(adbRuntimeConfig.exported)
        assertTrue(adbVpn.directBootAware)
        assertTrue(adbFreezer.directBootAware)
        assertTrue(adbGmsBinderPulse.directBootAware)
        assertTrue(adbRuntimeConfig.directBootAware)
        assertTrue(adbVpn.permission == "android.permission.DUMP")
        assertTrue(adbFreezer.permission == "android.permission.DUMP")
        assertTrue(adbNotification.permission == "android.permission.DUMP")
        assertTrue(adbGmsBinderPulse.permission == "android.permission.DUMP")
        assertTrue(adbRuntimeConfig.permission == "android.permission.DUMP")
        assertFalse(adbNotification.processName == service.processName)
        assertTrue(adbVpn.processName == service.processName)
        assertTrue(adbFreezer.processName == service.processName)
        assertTrue(adbGmsBinderPulse.processName == service.processName)
        assertTrue(adbRuntimeConfig.processName == service.processName)
    }

    @Test
    fun manifestHasBatteryExemptionButNoLocationOrForbiddenServices() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()
        assertTrue(
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in permissions
        )
        assertFalse(permissions.any { it.contains("ACCESS_") && it.contains("LOCATION") })
        val services = packageInfo.services.orEmpty()
        assertTrue(services.any { it.name.endsWith("ArrivalNotificationListener") })
        assertFalse(services.any { it.name.contains("CompanionDeviceService") })
        assertFalse(services.any { it.name.contains("AccessibilityService") })
    }

    @Test
    fun statusProviderActuallyRunsInKeeperProcess() {
        val status = requireNotNull(GuardianStatusClient.status(context))
        val keeperPid = status.getInt(LuonnotarPreferences.KEY_KEEPER_PROCESS_PID, 0)
        assertTrue(keeperPid > 0)
        assertNotEquals(Process.myPid(), keeperPid)
        assertTrue(
            status.getString(LuonnotarPreferences.KEY_RUNTIME_BOOT_ID, "") ==
                File("/proc/sys/kernel/random/boot_id").readText().trim()
        )
    }

    @Test
    fun activeGuardianCanSynchronouslyRescheduleRecoveryAlarm() {
        val before = requireNotNull(GuardianStatusClient.status(context))
        val enabled = before.getBoolean(LuonnotarPreferences.KEY_ENABLED, false)
        val paused = before.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
        val scheduled = GuardianStatusClient.scheduleRecoveryAlarm(context)
        if (enabled && !paused) {
            assertTrue(scheduled)
            val after = requireNotNull(GuardianStatusClient.status(context))
            assertTrue(
                after.getLong(LuonnotarPreferences.KEY_ALARM_SCHEDULED_ELAPSED, 0L) > 0L
            )
        } else {
            assertFalse(scheduled)
        }
        assertTrue(LabAlarmScheduler.MIN_IDLE_INTERVAL_MS >= 9 * 60_000L)
    }

    @Test
    fun repeatedStartStopOneHundredTimesLeavesNoResources() {
        val original = requireNotNull(GuardianStatusClient.status(context))
        val originallyEnabled = original.getBoolean(
            LuonnotarPreferences.KEY_ENABLED,
            false
        )
        val runId = SystemClock.elapsedRealtime()
        try {
            assertTrue(GuardianStatusClient.setEnabled(context, false))
            context.stopService(
                Intent(context, FcmGuardianService::class.java)
            )
            assertTrue(
                waitUntil(10_000L) {
                    val status =
                        GuardianStatusClient.status(context)
                            ?: return@waitUntil false
                    status.getInt(LuonnotarPreferences.KEY_PID, 0) == 0
                }
            )
            SystemClock.sleep(500L)
            repeat(100) { iteration ->
                val startReason =
                    "instrumentation_stress_${runId}_$iteration"
                assertTrue(
                    "enable failed at iteration $iteration",
                    GuardianStatusClient.setEnabled(context, true)
                )
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FcmGuardianService::class.java)
                        .setAction(FcmGuardianService.ACTION_START)
                        .putExtra(
                            FcmGuardianService.EXTRA_START_REASON,
                            startReason
                        )
                )
                assertTrue(
                    "service did not become live at iteration $iteration",
                    waitUntil(5_000L) {
                        val status =
                            GuardianStatusClient.status(context)
                                ?: return@waitUntil false
                        status.getInt(LuonnotarPreferences.KEY_PID, 0) > 0 &&
                            status.getString(
                                LuonnotarPreferences.KEY_LAST_START_REASON,
                                ""
                            ) == startReason &&
                            status.getBoolean(
                                LuonnotarPreferences.KEY_WAKE_LOCK,
                                false
                            )
                    }
                )
                assertTrue(
                    "disable failed at iteration $iteration",
                    GuardianStatusClient.setEnabled(context, false)
                )
                context.startService(
                    Intent(context, FcmGuardianService::class.java)
                        .setAction(FcmGuardianService.ACTION_STOP)
                )
                assertTrue(
                    "service did not stop at iteration $iteration",
                    waitUntil(5_000L) {
                        val status =
                            GuardianStatusClient.status(context)
                                ?: return@waitUntil false
                        status.getInt(LuonnotarPreferences.KEY_PID, 0) == 0 &&
                            !status.getBoolean(
                                LuonnotarPreferences.KEY_WAKE_LOCK,
                                true
                            ) &&
                            !status.getBoolean(
                                LuonnotarPreferences.KEY_WIFI_LOCK,
                                true
                        )
                    }
                )
                SystemClock.sleep(250L)
            }
            assertTrue(
                "service resources remained after 100 stop cycles",
                waitUntil(15_000L) {
                    val status =
                        GuardianStatusClient.status(context) ?: return@waitUntil false
                    status.getInt(LuonnotarPreferences.KEY_PID, 0) == 0 &&
                        !status.getBoolean(
                            LuonnotarPreferences.KEY_WAKE_LOCK,
                            true
                        ) &&
                        !status.getBoolean(
                            LuonnotarPreferences.KEY_WIFI_LOCK,
                            true
                        ) &&
                        !status.getBoolean(
                            LuonnotarPreferences.KEY_PROBE_IN_FLIGHT,
                            true
                        )
                }
            )
        } finally {
            GuardianStatusClient.setEnabled(context, originallyEnabled)
            if (originallyEnabled) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FcmGuardianService::class.java)
                        .setAction(FcmGuardianService.ACTION_START)
                        .putExtra(
                            FcmGuardianService.EXTRA_START_REASON,
                            "instrumentation_restore"
                        )
                )
            }
        }
    }

    @Test
    fun rapidPauseResumeKeepsLatestStartForeground() {
        val original = requireNotNull(GuardianStatusClient.status(context))
        val originallyEnabled = original.getBoolean(
            LuonnotarPreferences.KEY_ENABLED,
            false
        )
        val originallyPaused = original.getBoolean(
            LuonnotarPreferences.KEY_PAUSED,
            false
        )
        try {
            assertTrue(GuardianStatusClient.setEnabled(context, true))
            ContextCompat.startForegroundService(
                context,
                Intent(context, FcmGuardianService::class.java)
                    .setAction(FcmGuardianService.ACTION_START)
                    .putExtra(
                        FcmGuardianService.EXTRA_START_REASON,
                        "instrumentation_pause_resume_setup"
                    )
            )
            assertTrue(
                waitUntil(10_000L) {
                    val status = GuardianStatusClient.status(context)
                        ?: return@waitUntil false
                    status.getInt(LuonnotarPreferences.KEY_PID, 0) > 0
                }
            )

            repeat(100) {
                context.startService(
                    Intent(context, FcmGuardianService::class.java)
                        .setAction(FcmGuardianService.ACTION_PAUSE)
                )
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FcmGuardianService::class.java)
                        .setAction(FcmGuardianService.ACTION_RESUME)
                        .putExtra(
                            FcmGuardianService.EXTRA_START_REASON,
                            "instrumentation_pause_resume_$it"
                        )
                )
            }

            assertTrue(
                "latest resume did not keep the service alive",
                waitUntil(15_000L) {
                    val status = GuardianStatusClient.status(context)
                        ?: return@waitUntil false
                    status.getBoolean(
                        LuonnotarPreferences.KEY_ENABLED,
                        false
                    ) &&
                        !status.getBoolean(
                            LuonnotarPreferences.KEY_PAUSED,
                            true
                        ) &&
                        status.getInt(
                            LuonnotarPreferences.KEY_PID,
                            0
                        ) > 0
                }
            )
            val notificationManager =
                context.getSystemService(NotificationManager::class.java)
            if (notificationManager.areNotificationsEnabled()) {
                assertTrue(
                    "latest resume lost the foreground notification",
                    waitUntil(5_000L) {
                        notificationManager.activeNotifications.any {
                            it.id == NotificationChannelManager.NOTIFICATION_ID
                        }
                    }
                )
            }
        } finally {
            GuardianStatusClient.setEnabled(context, originallyEnabled)
            if (originallyEnabled) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FcmGuardianService::class.java)
                        .setAction(FcmGuardianService.ACTION_START)
                        .putExtra(
                            FcmGuardianService.EXTRA_START_REASON,
                            "instrumentation_restore_after_pause_resume"
                        )
                )
                if (originallyPaused) {
                    context.startService(
                        Intent(context, FcmGuardianService::class.java)
                            .setAction(FcmGuardianService.ACTION_PAUSE)
                    )
                }
            } else {
                context.startService(
                    Intent(context, FcmGuardianService::class.java)
                        .setAction(FcmGuardianService.ACTION_STOP)
                )
            }
        }
    }

    @Test
    fun diagnosticExportContainsRequiredStateWithoutRawBootId() {
        val rawBootId =
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        val output = LogManager.exportZip(context)
        try {
            ZipFile(output).use { zip ->
                val names = zip.entries().asSequence()
                    .map { it.name }
                    .toSet()
                assertTrue("missing device summary", "device-summary.json" in names)
                assertTrue(
                    "missing diagnostic manifest",
                    "diagnostic-manifest.json" in names
                )
                val summary = zip.getInputStream(
                    zip.getEntry("device-summary.json")
                ).bufferedReader().use { it.readText() }
                assertTrue("raw boot ID leaked", rawBootId !in summary)
                assertTrue("anonymous boot ID missing", "bootIdAnonymous" in summary)
                assertTrue(
                    "notification-listener state missing",
                    "notificationListenerAuthorized" in summary
                )
                assertTrue(
                    "battery-optimization state missing",
                    "batteryOptimizationExempt" in summary
                )
                assertTrue(
                    "ADB advice missing",
                    "adbVerificationAdvice" in summary
                )
                zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .forEach { entry ->
                        val text = zip.getInputStream(entry)
                            .bufferedReader()
                            .use { it.readText() }
                        assertTrue(
                            "raw boot ID leaked in ${entry.name}",
                            rawBootId !in text
                        )
                    }
            }
        } finally {
            output.delete()
        }
    }

    private fun waitUntil(
        timeoutMs: Long,
        condition: () -> Boolean
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(50L)
        }
        return condition()
    }
}
