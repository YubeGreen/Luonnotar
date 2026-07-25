package com.yubegreen.luonnotar

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yubegreen.luonnotar.receiver.BootCompletedReceiver
import com.yubegreen.luonnotar.receiver.ExactAlarmPermissionChangedReceiver
import com.yubegreen.luonnotar.receiver.LabAlarmScheduler
import com.yubegreen.luonnotar.service.FcmGuardianService
import com.yubegreen.luonnotar.service.GuardianStatusClient
import com.yubegreen.luonnotar.service.GuardianStatusProvider
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
}
