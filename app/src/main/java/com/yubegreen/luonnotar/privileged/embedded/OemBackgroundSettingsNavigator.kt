package com.yubegreen.luonnotar.privileged.embedded

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.yubegreen.luonnotar.privileged.BackgroundPolicyVendorFamily

internal data class OemBackgroundSettingsCandidate(
    val label: String,
    val action: String? = null,
    val packageName: String? = null,
    val className: String? = null,
    val appDetails: Boolean = false
)

internal object OemBackgroundSettingsPlan {
    fun candidates(family: BackgroundPolicyVendorFamily): List<OemBackgroundSettingsCandidate> {
        val vendor = when (family) {
            BackgroundPolicyVendorFamily.XIAOMI -> listOf(
                OemBackgroundSettingsCandidate(
                    label = "xiaomi_autostart_action",
                    action = "miui.intent.action.OP_AUTO_START"
                ),
                OemBackgroundSettingsCandidate(
                    label = "xiaomi_autostart_component",
                    packageName = "com.miui.securitycenter",
                    className = "com.miui.permcenter.autostart.AutoStartManagementActivity"
                ),
                OemBackgroundSettingsCandidate(
                    label = "xiaomi_battery_component",
                    packageName = "com.miui.powerkeeper",
                    className = "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity"
                )
            )
            BackgroundPolicyVendorFamily.VIVO -> listOf(
                OemBackgroundSettingsCandidate(
                    label = "vivo_autostart_component",
                    packageName = "com.vivo.permissionmanager",
                    className = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                ),
                OemBackgroundSettingsCandidate(
                    label = "iqoo_autostart_component",
                    packageName = "com.iqoo.secure",
                    className = "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                )
            )
            BackgroundPolicyVendorFamily.OPPO -> listOf(
                OemBackgroundSettingsCandidate(
                    label = "oplus_autostart_component",
                    packageName = "com.oplus.safecenter",
                    className = "com.oplus.safecenter.startupapp.StartupAppListActivity"
                ),
                OemBackgroundSettingsCandidate(
                    label = "coloros_autostart_component",
                    packageName = "com.coloros.safecenter",
                    className = "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            )
            BackgroundPolicyVendorFamily.HUAWEI -> listOf(
                OemBackgroundSettingsCandidate(
                    label = "huawei_startup_component",
                    packageName = "com.huawei.systemmanager",
                    className = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                ),
                OemBackgroundSettingsCandidate(
                    label = "honor_startup_component",
                    packageName = "com.hihonor.systemmanager",
                    className = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            )
            BackgroundPolicyVendorFamily.SAMSUNG,
            BackgroundPolicyVendorFamily.AOSP,
            BackgroundPolicyVendorFamily.UNKNOWN -> emptyList()
        }
        return vendor + listOf(
            OemBackgroundSettingsCandidate(
                label = "system_battery_optimization",
                action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            ),
            OemBackgroundSettingsCandidate(
                label = "application_details",
                appDetails = true
            )
        )
    }
}

object OemBackgroundSettingsNavigator {
    fun open(
        context: Context,
        family: BackgroundPolicyVendorFamily
    ): Result<String> {
        val errors = mutableListOf<String>()
        OemBackgroundSettingsPlan.candidates(family).forEach { candidate ->
            val intent = candidate.toIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val result = runCatching { context.startActivity(intent) }
            if (result.isSuccess) return Result.success(candidate.label)
            errors += "${candidate.label}:${result.exceptionOrNull()?.javaClass?.simpleName}"
        }
        return Result.failure(
            IllegalStateException(
                errors.joinToString(prefix = "no OEM background settings activity: ").take(1_500)
            )
        )
    }

    private fun OemBackgroundSettingsCandidate.toIntent(context: Context): Intent = when {
        appDetails -> Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        packageName != null && className != null -> Intent().apply {
            component = ComponentName(packageName, className)
        }
        action != null -> Intent(action)
        else -> Intent(Settings.ACTION_SETTINGS)
    }
}
