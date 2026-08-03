package com.yubegreen.luonnotar.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yubegreen.luonnotar.BuildConfig
import com.yubegreen.luonnotar.service.FcmGuardianService
import com.yubegreen.luonnotar.service.GuardianProfilePolicy
import com.yubegreen.luonnotar.service.GuardianRuntimeProfile
import com.yubegreen.luonnotar.service.GuardianStatusClient
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences

/**
 * Shell-only runtime configuration bridge for unattended testing while the
 * device remains locked. The manifest protects this exported receiver with
 * android.permission.DUMP, which adb shell holds and ordinary apps do not.
 *
 * Unspecified fields are preserved. A successful mutation is committed as one
 * provider transaction, read back, and then reconciled by the keeper service.
 */
class AdbRuntimeConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val appContext = context.applicationContext
        val before = GuardianStatusClient.status(appContext)
        if (before == null) {
            finish(
                ok = false,
                reason = "status_unavailable",
                values = emptyMap()
            )
            return
        }

        val currentProfile = runCatching {
            GuardianRuntimeProfile.valueOf(
                before.getString(
                    LuonnotarPreferences.KEY_GUARDIAN_PROFILE,
                    GuardianProfilePolicy.defaultProfile(
                        GuardianProfilePolicy.isVivoFamily(
                            android.os.Build.MANUFACTURER,
                            android.os.Build.BRAND
                        )
                    ).name
                ).orEmpty()
            )
        }.getOrNull()
        if (currentProfile == null) {
            finish(
                ok = false,
                reason = "invalid_persisted_profile",
                values = emptyMap()
            )
            return
        }

        if (action == ACTION_STATUS) {
            finish(
                ok = true,
                reason = "",
                values = statusValues(
                    status = before,
                    profile = currentProfile,
                    changed = false,
                    serviceReloadRequested = false
                )
            )
            return
        }

        val requestedProfile = if (intent.hasExtra(EXTRA_PROFILE)) {
            runCatching {
                GuardianRuntimeProfile.valueOf(
                    intent.getStringExtra(EXTRA_PROFILE).orEmpty()
                )
            }.getOrNull()
        } else {
            currentProfile
        }
        if (requestedProfile == null) {
            finish(
                ok = false,
                reason = "invalid_profile",
                values = statusValues(
                    before,
                    currentProfile,
                    changed = false,
                    serviceReloadRequested = false
                )
            )
            return
        }

        val currentLevel = before.getInt(
            LuonnotarPreferences.KEY_LAB_EXTREME_LEVEL,
            0
        ).coerceIn(0, 4)
        val requestedLevel = if (intent.hasExtra(EXTRA_LAB_LEVEL)) {
            intent.getIntExtra(EXTRA_LAB_LEVEL, -1)
        } else {
            currentLevel
        }
        if (requestedLevel !in 0..4) {
            finish(
                ok = false,
                reason = "invalid_lab_level",
                values = statusValues(
                    before,
                    currentProfile,
                    changed = false,
                    serviceReloadRequested = false
                )
            )
            return
        }

        val desiredExperiments = GuardianProfilePolicy.sanitizeExperiments(
            requestedProfile,
            GuardianProfilePolicy.experimentKeys.associateWith { key ->
                before.getBoolean(key, false)
            }
        ).toMutableMap()
        var mutationRequested =
            intent.hasExtra(EXTRA_PROFILE) || intent.hasExtra(EXTRA_LAB_LEVEL)
        GuardianProfilePolicy.adbMutableExperimentKeys.forEach { key ->
            if (intent.hasExtra(key)) {
                desiredExperiments[key] = intent.getBooleanExtra(
                    key,
                    desiredExperiments[key] == true
                )
                mutationRequested = true
            }
        }
        if (!mutationRequested) {
            finish(
                ok = false,
                reason = "no_mutations_requested",
                values = statusValues(
                    before,
                    currentProfile,
                    changed = false,
                    serviceReloadRequested = false
                )
            )
            return
        }

        val validationFailure = GuardianProfilePolicy.runtimeConfigError(
            requestedProfile,
            desiredExperiments
        )
        if (validationFailure != null) {
            finish(
                ok = false,
                reason = validationFailure,
                values = statusValues(
                    before,
                    currentProfile,
                    changed = false,
                    serviceReloadRequested = false
                )
            )
            return
        }

        val changed =
            requestedProfile != currentProfile ||
                requestedLevel != currentLevel ||
                GuardianProfilePolicy.experimentKeys.any { key ->
                    desiredExperiments[key] != before.getBoolean(key, false)
                }

        val committed = GuardianStatusClient.setRuntimeConfig(
            appContext,
            requestedProfile,
            requestedLevel,
            desiredExperiments
        )
        if (!committed) {
            finish(
                ok = false,
                reason = "commit_failed",
                values = statusValues(
                    before,
                    currentProfile,
                    changed = false,
                    serviceReloadRequested = false
                )
            )
            return
        }

        val after = GuardianStatusClient.status(appContext)
        val verified = after != null &&
            after.getString(
                LuonnotarPreferences.KEY_GUARDIAN_PROFILE,
                ""
            ) == requestedProfile.name &&
            after.getInt(
                LuonnotarPreferences.KEY_LAB_EXTREME_LEVEL,
                -1
            ) == requestedLevel &&
            GuardianProfilePolicy.experimentKeys.all { key ->
                after.getBoolean(key, false) == desiredExperiments[key]
            }
        if (!verified || after == null) {
            finish(
                ok = false,
                reason = "readback_verification_failed",
                values = statusValues(
                    before,
                    currentProfile,
                    changed = changed,
                    serviceReloadRequested = false
                )
            )
            return
        }

        val guardianActive =
            after.getBoolean(LuonnotarPreferences.KEY_ENABLED, false) &&
                !after.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
        val reloadResult = if (changed && guardianActive) {
            FcmGuardianService.requestInProcessRuntimeConfigReload(
                "adb_runtime_config_changed"
            )
        } else {
            FcmGuardianService.Companion.InProcessReloadResult.DEFERRED
        }
        val serviceReloadRequested =
            reloadResult == FcmGuardianService.Companion.InProcessReloadResult.APPLIED
        val ok =
            reloadResult != FcmGuardianService.Companion.InProcessReloadResult.FAILED
        val reason = if (ok) "" else "service_reload_failed"
        LogManager.event(
            appContext,
            "adb_runtime_config_applied",
            mapOf(
                "profile" to requestedProfile.name,
                "labLevel" to requestedLevel,
                "changed" to changed,
                "guardianActive" to guardianActive,
                "serviceReloadRequested" to serviceReloadRequested,
                "ok" to ok,
                "reason" to reason
            )
        )
        finish(
            ok = ok,
            reason = reason,
            values = statusValues(
                after,
                requestedProfile,
                changed,
                serviceReloadRequested
            )
        )
    }

    private fun statusValues(
        status: android.os.Bundle,
        profile: GuardianRuntimeProfile,
        changed: Boolean,
        serviceReloadRequested: Boolean
    ): Map<String, Any> = linkedMapOf<String, Any>().apply {
        put("schema", 2)
        put("versionName", BuildConfig.VERSION_NAME)
        put("profile", profile.name)
        put(
            "lab_level",
            status.getInt(LuonnotarPreferences.KEY_LAB_EXTREME_LEVEL, 0)
        )
        put(
            "guardian_enabled",
            status.getBoolean(LuonnotarPreferences.KEY_ENABLED, false)
        )
        put(
            "guardian_paused",
            status.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
        )
        put("changed", changed)
        put("service_reload_requested", serviceReloadRequested)
        GuardianProfilePolicy.adbMutableExperimentKeys.forEach { key ->
            put(key, status.getBoolean(key, false))
        }
    }

    private fun finish(
        ok: Boolean,
        reason: String,
        values: Map<String, Any>
    ) {
        val wire = linkedMapOf<String, Any>(
            "ok" to ok,
            "reason" to reason
        ).apply { putAll(values) }
            .entries
            .joinToString(";") { (key, value) -> "$key=$value" }
        setResultCode(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        setResultData(wire)
    }

    companion object {
        const val ACTION_STATUS =
            "com.yubegreen.luonnotar.action.ADB_RUNTIME_CONFIG_STATUS"
        const val ACTION_SET =
            "com.yubegreen.luonnotar.action.ADB_SET_RUNTIME_CONFIG"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_LAB_LEVEL = "lab_level"

        private val SUPPORTED_ACTIONS = setOf(ACTION_STATUS, ACTION_SET)
    }
}
