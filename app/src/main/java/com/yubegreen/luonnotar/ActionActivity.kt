package com.yubegreen.luonnotar

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.yubegreen.luonnotar.monitor.AdbVpnEvidencePolicy
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianManager
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianNotificationPolicy
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianNotifier
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
        const val ACTION_OPEN_WIRELESS_DEBUGGING =
            "com.yubegreen.luonnotar.action.OPEN_WIRELESS_DEBUGGING"
        const val ACTION_START_EMBEDDED_GUARDIAN =
            "com.yubegreen.luonnotar.action.START_EMBEDDED_GUARDIAN"
        const val EXTRA_EMBEDDED_NOTIFICATION_SOURCE =
            "embedded_notification_source"
        const val EXTRA_EMBEDDED_NOTIFICATION_BOOT_ACTION =
            "embedded_notification_boot_action"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            ACTION_OPEN_PROTON, ACTION_OPEN_VPN_APP -> openVpnApp()
            ACTION_OPEN_VPN_SETTINGS -> openVpnSettings()
            ACTION_RECOVER_GUARDIAN -> recoverGuardian()
            ACTION_OPEN_WIRELESS_DEBUGGING -> openWirelessDebugging(
                source = "setup_notification_wireless_action",
                bootAction = ""
            )
            ACTION_START_EMBEDDED_GUARDIAN -> if (
                EmbeddedGuardianNotificationPolicy.shouldStartSetup(intent?.action)
            ) {
                startEmbeddedGuardianFromNotification()
            }
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
            vpnPresent = status.getBoolean(LuonnotarPreferences.KEY_VPN, false),
            verifiedSessionFingerprint = status.getString(
                LuonnotarPreferences.KEY_ADB_SESSION_FINGERPRINT,
                ""
            ).orEmpty(),
            currentSessionFingerprint = status.getString(
                LuonnotarPreferences.KEY_VPN_SESSION_FINGERPRINT,
                ""
            ).orEmpty(),
            currentProviderPackage = status.getString(
                LuonnotarPreferences.KEY_VPN_PROVIDER_PACKAGE,
                ""
            )
        )

    private fun openVpnSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        LogManager.event(this, "user_opened_vpn_settings")
    }


    private fun startEmbeddedGuardianFromNotification() {
        val source = intent.getStringExtra(EXTRA_EMBEDDED_NOTIFICATION_SOURCE)
            .orEmpty().ifBlank { "reboot_reminder_start_action" }
        val bootAction = intent.getStringExtra(EXTRA_EMBEDDED_NOTIFICATION_BOOT_ACTION).orEmpty()
        LogManager.event(
            this,
            "embedded_notification_start_action_received",
            EmbeddedGuardianNotifier.eventFields(this, source, bootAction)
        )
        EmbeddedGuardianNotificationPolicy.executeStartAction(
            action = intent.action,
            startSetup = {
                LogManager.event(
                    this,
                    "embedded_notification_start_setup_requested",
                    EmbeddedGuardianNotifier.eventFields(this, source, bootAction)
                )
                EmbeddedGuardianManager.startSetup(this, source)
            },
            onStartFailure = { error ->
                LogManager.event(
                    this,
                    "embedded_notification_start_setup_failed",
                    EmbeddedGuardianNotifier.eventFields(this, source, bootAction) +
                        mapOf("error" to error.toString())
                )
            },
            openWirelessSettings = { openWirelessDebugging(source, bootAction) }
        )
    }

    private fun openWirelessDebugging(source: String, bootAction: String) {
        var route = "unavailable"
        for (settingsAction in EmbeddedGuardianNotificationPolicy.wirelessSettingsActions) {
            if (runCatching { startActivity(Intent(settingsAction)) }.isSuccess) {
                route = if (settingsAction == EmbeddedGuardianNotificationPolicy.wirelessSettingsActions.first()) {
                    "wireless_debugging"
                } else {
                    "developer_options"
                }
                break
            }
        }
        if (route == "unavailable" && EmbeddedGuardianNotificationPolicy.fallsBackToMainActivity()) {
            if (runCatching {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .putExtra(MainActivity.EXTRA_SCROLL_TO_EMBEDDED_GUARDIAN, true)
                    )
                }.isSuccess
            ) {
                route = "main_activity"
            }
        }
        LogManager.event(
            this,
            "embedded_wireless_settings_opened",
            EmbeddedGuardianNotifier.eventFields(this, source, bootAction) +
                mapOf("route" to route)
        )
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
