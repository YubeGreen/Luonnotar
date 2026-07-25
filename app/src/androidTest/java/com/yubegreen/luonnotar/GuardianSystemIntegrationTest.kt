package com.yubegreen.luonnotar

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
import com.yubegreen.luonnotar.receiver.ExactAlarmPermissionChangedReceiver
import com.yubegreen.luonnotar.receiver.LabAlarmScheduler
import com.yubegreen.luonnotar.service.FcmGuardianService
import com.yubegreen.luonnotar.service.GuardianStatusClient
import com.yubegreen.luonnotar.service.GuardianStatusProvider
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import com.yubegreen.luonnotar.util.LogManager
import org.junit.Assert.assertFalse
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
        val exactAlarmPermissionReceiver = context.packageManager.getReceiverInfo(
            ComponentName(context, ExactAlarmPermissionChangedReceiver::class.java),
            PackageManager.GET_META_DATA
        )

        assertFalse(boot.exported)
        assertFalse(exactAlarmPermissionReceiver.exported)
        assertTrue(boot.directBootAware)
        assertFalse(service.exported)
        assertTrue(service.directBootAware)
        assertFalse(provider.exported)
        assertTrue(provider.directBootAware)
        assertTrue(boot.processName.endsWith(":keeper"))
        assertTrue(service.processName.endsWith(":keeper"))
        assertTrue(provider.processName.endsWith(":keeper"))
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
        try {
            repeat(100) { iteration ->
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
                            "instrumentation_stress_$iteration"
                        )
                )
                assertTrue(
                    "disable failed at iteration $iteration",
                    GuardianStatusClient.setEnabled(context, false)
                )
                context.startService(
                    Intent(context, FcmGuardianService::class.java)
                        .setAction(FcmGuardianService.ACTION_STOP)
                )
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
