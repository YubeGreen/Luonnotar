package com.yubegreen.luonnotar.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.yubegreen.luonnotar.util.LuonnotarPreferences

enum class GuardianRuntimeProfile {
    STANDARD,
    IQOO_COOPERATIVE,
    ADB_PASSIVE,
    LAB_EXTREME
}

data class GuardianExperimentSettings(
    val permanentCpuLock: Boolean,
    val screenOffCpuGuard: Boolean,
    val scopedCpuLock: Boolean,
    val highPerfWifiLock: Boolean,
    val screenEventProbe: Boolean,
    val periodicDns: Boolean,
    val periodicHttps: Boolean,
    val automaticMtalk: Boolean,
    val persistentNetworkLease: Boolean,
    val persistentHeartbeatSocket: Boolean,
    val frequentNotificationRefresh: Boolean
)

data class GuardianRuntimeSettings(
    val profile: GuardianRuntimeProfile,
    val experiments: GuardianExperimentSettings
) {
    val cooperative: Boolean
        get() = profile == GuardianRuntimeProfile.IQOO_COOPERATIVE

    val passive: Boolean
        get() = profile == GuardianRuntimeProfile.ADB_PASSIVE
}

object GuardianProfilePolicy {
    const val SCREEN_OFF_QUIET_WINDOW_MS = 120_000L
    const val STARTUP_STABILIZATION_MS = 2_000L
    const val SCOPED_CPU_LOCK_TIMEOUT_MS = 10_000L
    const val HEARTBEAT_PERSIST_INTERVAL_MS = 60_000L
    const val NORMAL_NOTIFICATION_REFRESH_MS = 10 * 60_000L
    const val FREQUENT_NOTIFICATION_REFRESH_MS = 60_000L
    const val WHOLE_PROBE_DEADLINE_MS = 30_000L

    val experimentKeys = linkedSetOf(
        LuonnotarPreferences.KEY_EXPERIMENT_PERMANENT_CPU_LOCK,
        LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD,
        LuonnotarPreferences.KEY_EXPERIMENT_SCOPED_CPU_LOCK,
        LuonnotarPreferences.KEY_EXPERIMENT_HIGH_PERF_WIFI_LOCK,
        LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_EVENT_PROBE,
        LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_DNS,
        LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_HTTPS,
        LuonnotarPreferences.KEY_EXPERIMENT_AUTOMATIC_MTALK,
        LuonnotarPreferences.KEY_EXPERIMENT_PERSISTENT_NETWORK_LEASE,
        LuonnotarPreferences.KEY_EXPERIMENT_PERSISTENT_HEARTBEAT_SOCKET,
        LuonnotarPreferences.KEY_EXPERIMENT_FREQUENT_NOTIFICATION_REFRESH,
        LuonnotarPreferences.KEY_MONITOR_GMS,
        LuonnotarPreferences.KEY_MONITOR_WHATSAPP,
        LuonnotarPreferences.KEY_MONITOR_WHATSAPP_BUSINESS
    )

    val labOnlyExperimentKeys = setOf(
        LuonnotarPreferences.KEY_EXPERIMENT_PERMANENT_CPU_LOCK,
        LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_EVENT_PROBE
    )

    val passiveDisabledExperimentKeys = setOf(
        LuonnotarPreferences.KEY_EXPERIMENT_PERMANENT_CPU_LOCK,
        LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD,
        LuonnotarPreferences.KEY_EXPERIMENT_SCOPED_CPU_LOCK,
        LuonnotarPreferences.KEY_EXPERIMENT_HIGH_PERF_WIFI_LOCK,
        LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_EVENT_PROBE,
        LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_DNS,
        LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_HTTPS,
        LuonnotarPreferences.KEY_EXPERIMENT_AUTOMATIC_MTALK,
        LuonnotarPreferences.KEY_EXPERIMENT_PERSISTENT_NETWORK_LEASE,
        LuonnotarPreferences.KEY_EXPERIMENT_PERSISTENT_HEARTBEAT_SOCKET,
        LuonnotarPreferences.KEY_EXPERIMENT_FREQUENT_NOTIFICATION_REFRESH
    )

    /**
     * Configuration keys accepted by the DUMP-protected ADB bridge.
     * The bridge is shell-only; runtime policy validation still applies.
     */
    val adbMutableExperimentKeys = linkedSetOf(
        LuonnotarPreferences.KEY_EXPERIMENT_PERMANENT_CPU_LOCK,
        LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD,
        LuonnotarPreferences.KEY_EXPERIMENT_SCOPED_CPU_LOCK,
        LuonnotarPreferences.KEY_EXPERIMENT_HIGH_PERF_WIFI_LOCK,
        LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_EVENT_PROBE,
        LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_DNS,
        LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_HTTPS,
        LuonnotarPreferences.KEY_EXPERIMENT_AUTOMATIC_MTALK,
        LuonnotarPreferences.KEY_EXPERIMENT_PERSISTENT_NETWORK_LEASE,
        LuonnotarPreferences.KEY_EXPERIMENT_PERSISTENT_HEARTBEAT_SOCKET,
        LuonnotarPreferences.KEY_EXPERIMENT_FREQUENT_NOTIFICATION_REFRESH,
        LuonnotarPreferences.KEY_MONITOR_GMS,
        LuonnotarPreferences.KEY_MONITOR_WHATSAPP,
        LuonnotarPreferences.KEY_MONITOR_WHATSAPP_BUSINESS
    )

    fun experimentToggleError(
        profile: GuardianRuntimeProfile,
        key: String,
        enabled: Boolean
    ): String? {
        if (key !in experimentKeys) return "unknown_experiment_key"
        if (
            enabled &&
            key in labOnlyExperimentKeys &&
            profile != GuardianRuntimeProfile.LAB_EXTREME
        ) {
            return "lab_profile_required:$key"
        }
        if (
            enabled &&
            profile == GuardianRuntimeProfile.ADB_PASSIVE &&
            key in passiveDisabledExperimentKeys
        ) {
            return "adb_passive_forbids:$key"
        }
        return null
    }

    fun sanitizeExperiments(
        profile: GuardianRuntimeProfile,
        experiments: Map<String, Boolean>
    ): Map<String, Boolean> =
        experimentKeys.associateWith { key ->
            val requested = experiments[key] == true
            when {
                key in labOnlyExperimentKeys &&
                    profile != GuardianRuntimeProfile.LAB_EXTREME -> false
                profile == GuardianRuntimeProfile.ADB_PASSIVE &&
                    key in passiveDisabledExperimentKeys -> false
                else -> requested
            }
        }

    fun runtimeConfigError(
        profile: GuardianRuntimeProfile,
        experiments: Map<String, Boolean>
    ): String? {
        val unknown = experiments.keys.firstOrNull { it !in experimentKeys }
        if (unknown != null) return "unknown_experiment_key:$unknown"

        val labViolation = labOnlyExperimentKeys.firstOrNull { key ->
            experiments[key] == true &&
                profile != GuardianRuntimeProfile.LAB_EXTREME
        }
        if (labViolation != null) {
            return "lab_profile_required:$labViolation"
        }

        if (profile == GuardianRuntimeProfile.ADB_PASSIVE) {
            val passiveViolation =
                passiveDisabledExperimentKeys.firstOrNull { key ->
                    experiments[key] == true
                }
            if (passiveViolation != null) {
                return "adb_passive_forbids:$passiveViolation"
            }
        }
        return null
    }

    fun isVivoFamily(manufacturer: String, brand: String): Boolean {
        val vendor = "$manufacturer $brand".lowercase()
        return "vivo" in vendor || "iqoo" in vendor
    }

    fun defaultProfile(vivoFamily: Boolean): GuardianRuntimeProfile =
        if (vivoFamily) {
            GuardianRuntimeProfile.IQOO_COOPERATIVE
        } else {
            GuardianRuntimeProfile.STANDARD
        }

    fun defaults(
        profile: GuardianRuntimeProfile
    ): GuardianExperimentSettings =
        when (profile) {
            GuardianRuntimeProfile.IQOO_COOPERATIVE ->
                GuardianExperimentSettings(
                    permanentCpuLock = false,
                    screenOffCpuGuard = true,
                    scopedCpuLock = true,
                    highPerfWifiLock = false,
                    screenEventProbe = false,
                    periodicDns = false,
                    periodicHttps = false,
                    automaticMtalk = false,
                    persistentNetworkLease = false,
                    persistentHeartbeatSocket = false,
                    frequentNotificationRefresh = false
                )

            GuardianRuntimeProfile.STANDARD ->
                GuardianExperimentSettings(
                    permanentCpuLock = false,
                    screenOffCpuGuard = true,
                    scopedCpuLock = true,
                    highPerfWifiLock = false,
                    screenEventProbe = false,
                    periodicDns = true,
                    periodicHttps = true,
                    automaticMtalk = false,
                    persistentNetworkLease = false,
                    persistentHeartbeatSocket = false,
                    frequentNotificationRefresh = false
                )

            GuardianRuntimeProfile.ADB_PASSIVE ->
                GuardianExperimentSettings(
                    permanentCpuLock = false,
                    screenOffCpuGuard = false,
                    scopedCpuLock = false,
                    highPerfWifiLock = false,
                    screenEventProbe = false,
                    periodicDns = false,
                    periodicHttps = false,
                    automaticMtalk = false,
                    persistentNetworkLease = false,
                    persistentHeartbeatSocket = false,
                    frequentNotificationRefresh = false
                )

            GuardianRuntimeProfile.LAB_EXTREME ->
                labLevel(0)
        }

    fun labLevel(level: Int): GuardianExperimentSettings {
        val safeLevel = level.coerceIn(0, 4)

        return GuardianExperimentSettings(
            permanentCpuLock = safeLevel >= 4,
            screenOffCpuGuard = safeLevel >= 1,
            scopedCpuLock = safeLevel in 1..4,
            highPerfWifiLock = safeLevel >= 4,
            screenEventProbe = safeLevel >= 2,
            periodicDns = safeLevel >= 3,
            periodicHttps = safeLevel >= 3,
            automaticMtalk = false,
            persistentNetworkLease = false,
            persistentHeartbeatSocket = false,
            frequentNotificationRefresh = false
        )
    }

    fun ensureDefaults(
        context: Context,
        prefs: SharedPreferences
    ) {
        val vivoFamily =
            isVivoFamily(Build.MANUFACTURER, Build.BRAND)
        val profile = readProfile(prefs, vivoFamily)
        val defaults = defaults(profile)
        val editor = prefs.edit()
        var changed = false

        if (!prefs.contains(LuonnotarPreferences.KEY_GUARDIAN_PROFILE)) {
            editor.putString(
                LuonnotarPreferences.KEY_GUARDIAN_PROFILE,
                profile.name
            )
            changed = true
        }

        val values = mapOf(
            LuonnotarPreferences.KEY_EXPERIMENT_PERMANENT_CPU_LOCK to
                defaults.permanentCpuLock,
            LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD to
                defaults.screenOffCpuGuard,
            LuonnotarPreferences.KEY_EXPERIMENT_SCOPED_CPU_LOCK to
                defaults.scopedCpuLock,
            LuonnotarPreferences.KEY_EXPERIMENT_HIGH_PERF_WIFI_LOCK to
                defaults.highPerfWifiLock,
            LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_EVENT_PROBE to
                defaults.screenEventProbe,
            LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_DNS to
                defaults.periodicDns,
            LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_HTTPS to
                defaults.periodicHttps,
            LuonnotarPreferences.KEY_EXPERIMENT_AUTOMATIC_MTALK to
                defaults.automaticMtalk,
            LuonnotarPreferences.KEY_EXPERIMENT_PERSISTENT_NETWORK_LEASE to
                defaults.persistentNetworkLease,
            LuonnotarPreferences.KEY_EXPERIMENT_PERSISTENT_HEARTBEAT_SOCKET to
                defaults.persistentHeartbeatSocket,
            LuonnotarPreferences.KEY_EXPERIMENT_FREQUENT_NOTIFICATION_REFRESH to
                defaults.frequentNotificationRefresh
        )

        values.forEach { (key, value) ->
            if (!prefs.contains(key)) {
                editor.putBoolean(key, value)
                changed = true
            }
        }

        if (
            !prefs.contains(
                LuonnotarPreferences
                    .KEY_EXPERIMENT_PERSISTENT_HEARTBEAT_SOCKET
            )
        ) {
            editor.putBoolean(
                LuonnotarPreferences
                    .KEY_EXPERIMENT_PERSISTENT_HEARTBEAT_SOCKET,
                prefs.getBoolean(
                    LuonnotarPreferences
                        .KEY_EXPERIMENT_PERSISTENT_MTALK_SOCKET,
                    false
                )
            )
            changed = true
        }
        if (
            prefs.getBoolean(
                LuonnotarPreferences
                    .KEY_EXPERIMENT_PERSISTENT_MTALK_SOCKET,
                false
            )
        ) {
            editor.putBoolean(
                LuonnotarPreferences
                    .KEY_EXPERIMENT_PERSISTENT_MTALK_SOCKET,
                false
            )
            changed = true
        }

        if (
            !prefs.getBoolean(
                LuonnotarPreferences
                    .KEY_SCREEN_OFF_CPU_GUARD_INTEGRATED_V2,
                false
            )
        ) {
            if (
                profile == GuardianRuntimeProfile.STANDARD ||
                profile == GuardianRuntimeProfile.IQOO_COOPERATIVE
            ) {
                editor.putBoolean(
                    LuonnotarPreferences
                        .KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD,
                    true
                )
            }
            editor.putBoolean(
                LuonnotarPreferences
                    .KEY_SCREEN_OFF_CPU_GUARD_INTEGRATED_V2,
                true
            )
            changed = true
        }

        mapOf(
            LuonnotarPreferences.KEY_MONITOR_GMS to true,
            LuonnotarPreferences.KEY_MONITOR_WHATSAPP to true,
            LuonnotarPreferences.KEY_MONITOR_WHATSAPP_BUSINESS to false
        ).forEach { (key, value) ->
            if (!prefs.contains(key)) {
                editor.putBoolean(key, value)
                changed = true
            }
        }

        if (changed) editor.apply()
    }

    fun read(
        context: Context,
        prefs: SharedPreferences
    ): GuardianRuntimeSettings {
        val profile = readProfile(
            prefs,
            isVivoFamily(Build.MANUFACTURER, Build.BRAND)
        )
        val defaults = defaults(profile)
        val labOnly =
            profile == GuardianRuntimeProfile.LAB_EXTREME

        return GuardianRuntimeSettings(
            profile = profile,
            experiments = GuardianExperimentSettings(
                permanentCpuLock =
                    labOnly && prefs.getBoolean(
                        LuonnotarPreferences
                            .KEY_EXPERIMENT_PERMANENT_CPU_LOCK,
                        defaults.permanentCpuLock
                    ),
                screenOffCpuGuard =
                    profile != GuardianRuntimeProfile.ADB_PASSIVE &&
                        prefs.getBoolean(
                            LuonnotarPreferences
                                .KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD,
                            defaults.screenOffCpuGuard
                        ),
                scopedCpuLock =
                    profile != GuardianRuntimeProfile.ADB_PASSIVE,
                highPerfWifiLock =
                    profile != GuardianRuntimeProfile.ADB_PASSIVE &&
                        prefs.getBoolean(
                            LuonnotarPreferences
                                .KEY_EXPERIMENT_HIGH_PERF_WIFI_LOCK,
                            defaults.highPerfWifiLock
                        ),
                screenEventProbe =
                    labOnly && prefs.getBoolean(
                        LuonnotarPreferences
                            .KEY_EXPERIMENT_SCREEN_EVENT_PROBE,
                        defaults.screenEventProbe
                    ),
                periodicDns =
                    prefs.getBoolean(
                        LuonnotarPreferences
                            .KEY_EXPERIMENT_PERIODIC_DNS,
                        defaults.periodicDns
                    ),
                periodicHttps =
                    prefs.getBoolean(
                        LuonnotarPreferences
                            .KEY_EXPERIMENT_PERIODIC_HTTPS,
                        defaults.periodicHttps
                    ),
                automaticMtalk =
                    prefs.getBoolean(
                        LuonnotarPreferences
                            .KEY_EXPERIMENT_AUTOMATIC_MTALK,
                        defaults.automaticMtalk
                    ),
                persistentNetworkLease =
                    profile != GuardianRuntimeProfile.ADB_PASSIVE &&
                        prefs.getBoolean(
                            LuonnotarPreferences
                                .KEY_EXPERIMENT_PERSISTENT_NETWORK_LEASE,
                            defaults.persistentNetworkLease
                        ),
                persistentHeartbeatSocket =
                    profile != GuardianRuntimeProfile.ADB_PASSIVE &&
                        prefs.getBoolean(
                            LuonnotarPreferences
                                .KEY_EXPERIMENT_PERSISTENT_HEARTBEAT_SOCKET,
                            defaults.persistentHeartbeatSocket
                        ),
                frequentNotificationRefresh =
                    prefs.getBoolean(
                        LuonnotarPreferences
                            .KEY_EXPERIMENT_FREQUENT_NOTIFICATION_REFRESH,
                        defaults.frequentNotificationRefresh
                    )
            )
        )
    }

    fun readProfile(
        prefs: SharedPreferences,
        vivoFamily: Boolean
    ): GuardianRuntimeProfile =
        runCatching {
            GuardianRuntimeProfile.valueOf(
                prefs.getString(
                    LuonnotarPreferences.KEY_GUARDIAN_PROFILE,
                    null
                ) ?: defaultProfile(vivoFamily).name
            )
        }.getOrDefault(defaultProfile(vivoFamily))

    fun quietWindowActive(
        nowElapsed: Long,
        quietUntilElapsed: Long
    ): Boolean =
        quietUntilElapsed > 0L &&
            nowElapsed < quietUntilElapsed

    fun notificationRefreshInterval(
        settings: GuardianExperimentSettings
    ): Long =
        if (settings.frequentNotificationRefresh) {
            FREQUENT_NOTIFICATION_REFRESH_MS
        } else {
            NORMAL_NOTIFICATION_REFRESH_MS
        }
}

object GuardianPassiveWindowPolicy {
    const val WINDOW_MS = 60_000L

    fun shouldClose(
        windowStartedElapsed: Long,
        nowElapsed: Long
    ): Boolean =
        windowStartedElapsed > 0L &&
            nowElapsed - windowStartedElapsed >= WINDOW_MS
}