package com.yubegreen.luonnotar

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.yubegreen.luonnotar.monitor.AdbVpnEvidencePolicy
import com.yubegreen.luonnotar.monitor.SupportedVpnProvider
import com.yubegreen.luonnotar.service.FcmGuardianService
import com.yubegreen.luonnotar.service.GuardianStatusClient
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import com.yubegreen.luonnotar.util.LogManager
import java.io.File

class ActionActivity : Activity() {
    companion object {
        const val ACTION_OPEN_PROTON = "com.yubegreen.luonnotar.action.OPEN_PROTON"
        const val ACTION_OPEN_VPN_APP = "com.yubegreen.luonnotar.action.OPEN_VPN_APP"
        const val ACTION_OPEN_VPN_SETTINGS = "com.yubegreen.luonnotar.action.OPEN_VPN_SETTINGS"
        const val ACTION_RECOVER_GUARDIAN =
            "com.yubegreen.luonnotar.action.RECOVER_GUARDIAN"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            ACTION_OPEN_PROTON, ACTION_OPEN_VPN_APP -> openVpnApp()
            ACTION_OPEN_VPN_SETTINGS -> openVpnSettings()
            ACTION_RECOVER_GUARDIAN -> recoverGuardian()
        }
        finish()
    }

    private fun openVpnApp() {
        val status = GuardianStatusClient.status(this)
        val importedPackage = status
            ?.takeIf(::adbEvidenceIsCurrent)
            ?.getString(LuonnotarPreferences.KEY_ADB_ACTIVE_VPN_PACKAGE)
            ?.takeIf(SupportedVpnProvider::isSupported)
        val alwaysOnPackage = runCatching {
            Settings.Secure.getString(contentResolver, "always_on_vpn_app")
        }.getOrNull()?.takeIf(SupportedVpnProvider::isSupported)
        val installed = SupportedVpnProvider.entries.filter {
            packageManager.getLaunchIntentForPackage(it.packageName) != null
        }
        val targetPackage =
            importedPackage ?: alwaysOnPackage ?: installed.singleOrNull()?.packageName
        if (targetPackage == null && installed.size > 1) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN_VPN_CHOOSER, true)
            )
            LogManager.event(this, "vpn_app_selection_requested")
            return
        }
        val launch = targetPackage?.let(packageManager::getLaunchIntentForPackage)
        val launched = launch != null && runCatching {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!launched) {
            openVpnSettings()
        }
        LogManager.event(
            this,
            "user_opened_vpn_app",
            mapOf("package" to (targetPackage ?: "settings"))
        )
    }

    private fun adbEvidenceIsCurrent(status: Bundle): Boolean =
        AdbVpnEvidencePolicy.isCurrent(
            verifiedElapsed = status.getLong(
                LuonnotarPreferences.KEY_ADB_VERIFIED_ELAPSED,
                0L
            ),
            nowElapsed = android.os.SystemClock.elapsedRealtime(),
            verifiedBootId = status.getString(
                LuonnotarPreferences.KEY_ADB_VERIFIED_BOOT_ID,
                ""
            ).orEmpty(),
            currentBootId = runCatching {
                File("/proc/sys/kernel/random/boot_id").readText().trim()
            }.getOrDefault("unavailable"),
            activePackage = status.getString(
                LuonnotarPreferences.KEY_ADB_ACTIVE_VPN_PACKAGE,
                ""
            ).orEmpty(),
            evidenceHash = status.getString(
                LuonnotarPreferences.KEY_ADB_EVIDENCE_HASH,
                ""
            ).orEmpty(),
            verifiedNetworkHandle = status.getLong(
                LuonnotarPreferences.KEY_ADB_NETWORK_HANDLE,
                -1L
            ),
            currentNetworkHandle = status.getLong(
                LuonnotarPreferences.KEY_NETWORK_HANDLE,
                -2L
            ),
            vpnPresent = status.getBoolean(LuonnotarPreferences.KEY_VPN, false)
        )

    private fun openVpnSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        LogManager.event(this, "user_opened_vpn_settings")
    }

    private fun recoverGuardian() {
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, FcmGuardianService::class.java)
                    .setAction(FcmGuardianService.ACTION_RECOVER)
                    .putExtra(
                        FcmGuardianService.EXTRA_START_REASON,
                        "user_tapped_recovery_notification"
                    )
            )
        }.onSuccess {
            LogManager.event(this, "user_requested_guardian_recovery")
        }.onFailure {
            LogManager.event(
                this,
                "user_guardian_recovery_failed",
                mapOf("error" to it.toString())
            )
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(
                        MainActivity.EXTRA_STATUS_MESSAGE,
                        "系统拒绝恢复守护服务：${it.javaClass.simpleName}"
                    )
            )
        }
    }
}
