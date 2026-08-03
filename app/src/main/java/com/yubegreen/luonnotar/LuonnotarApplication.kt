package com.yubegreen.luonnotar

import android.app.Application
import android.os.Build
import android.util.Log
import com.yubegreen.luonnotar.notification.NotificationChannelManager
import com.yubegreen.luonnotar.privileged.PrivilegedGuardianController
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianManager
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianNotifier
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences

class LuonnotarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val process = processName()
        if (process == packageName) {
            PrivilegedGuardianController.initialize(this)
            EmbeddedGuardianManager.initialize(this)
        }
        if (process.endsWith(":keeper")) {
            if (!LuonnotarPreferences.initializeKeeperBoot(this)) {
                Log.e(LogManager.TAG, "keeper boot evidence initialization failed")
            }
            if (!LuonnotarPreferences.initializeKeeperProcess(this)) {
                Log.e(LogManager.TAG, "keeper process evidence initialization failed")
            }
        }
        NotificationChannelManager.create(this)
        LogManager.initialize(this)
        LogManager.event(this, "application_created", mapOf("process" to process))
        if (process == packageName) {
            EmbeddedGuardianNotifier.reconcileRebootReminder(
                context = this,
                source = "application_on_create"
            )
        }
    }

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            runCatching {
                java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
            }.getOrDefault(packageName)
        }
}
