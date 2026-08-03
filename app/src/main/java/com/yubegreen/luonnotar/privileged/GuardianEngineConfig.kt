package com.yubegreen.luonnotar.privileged

import org.json.JSONArray
import org.json.JSONObject

/**
 * Configuration passed into the privileged process. Every externally supplied
 * identifier is validated before it can become a command argument.
 */
data class GuardianEngineConfig(
    val processTargets: List<String> = DEFAULT_PROCESS_TARGETS,
    val packageTargets: List<String> = DEFAULT_PACKAGE_TARGETS,
    val pollIntervalMs: Long = 15_000L,
    val reassertIntervalMs: Long = 60_000L,
    val tuningIntervalMs: Long = 15 * 60_000L,
    val stickyUnfreeze: Boolean = true,
    val tuneStandby: Boolean = true,
    val tuneAppOps: Boolean = true,
    val tuneDeviceIdle: Boolean = true,
    val tuneNetworkPolicy: Boolean = true,
    val tuneHibernation: Boolean = true,
    val rootCgroupThaw: Boolean = true,
    val gmsRecoveryEnabled: Boolean = false,
    val vendorEmergencyRecoveryEnabled: Boolean = true,
    val gmsFreezeThreshold: Int = 3,
    val gmsFreezeWindowMs: Long = 10 * 60_000L,
    val gmsAutomaticCooldownMs: Long = 6 * 60 * 60_000L,
    val gmsManualCooldownMs: Long = 2 * 60_000L,
    val gmsMaxRecoveriesPer24Hours: Int = 2,
    val gmsRestartWaitMs: Long = 45_000L,
    val gmsRestartPollMs: Long = 1_000L,
    val gmsTransportProbeIntervalMs: Long = 30_000L,
    val gmsTransportBadAuthWindowMs: Long = 10 * 60_000L,
    val gmsTransportMissingAfterBadAuthMs: Long = 90_000L,
    val gmsTransportLostMs: Long = 4 * 60_000L,
    val gmsTransportVerifyWaitMs: Long = 60_000L
) {
    fun normalized(): GuardianEngineConfig = copy(
        processTargets = processTargets
            .map(String::trim)
            .filter(::isSafeProcessName)
            .distinct()
            .ifEmpty { DEFAULT_PROCESS_TARGETS },
        packageTargets = packageTargets
            .map(String::trim)
            .filter(::isSafePackageName)
            .distinct()
            .ifEmpty { DEFAULT_PACKAGE_TARGETS },
        pollIntervalMs = pollIntervalMs.coerceIn(5_000L, 5 * 60_000L),
        reassertIntervalMs = reassertIntervalMs.coerceIn(15_000L, 30 * 60_000L),
        tuningIntervalMs = tuningIntervalMs.coerceIn(60_000L, 24 * 60 * 60_000L),
        gmsFreezeThreshold = gmsFreezeThreshold.coerceIn(2, 10),
        gmsFreezeWindowMs = gmsFreezeWindowMs.coerceIn(60_000L, 60 * 60_000L),
        gmsAutomaticCooldownMs = gmsAutomaticCooldownMs.coerceIn(30 * 60_000L, 24 * 60 * 60_000L),
        gmsManualCooldownMs = gmsManualCooldownMs.coerceIn(30_000L, 30 * 60_000L),
        gmsMaxRecoveriesPer24Hours = gmsMaxRecoveriesPer24Hours.coerceIn(1, 6),
        gmsRestartWaitMs = gmsRestartWaitMs.coerceIn(10_000L, 2 * 60_000L),
        gmsRestartPollMs = gmsRestartPollMs.coerceIn(500L, 5_000L),
        gmsTransportProbeIntervalMs = gmsTransportProbeIntervalMs.coerceIn(15_000L, 5 * 60_000L),
        gmsTransportBadAuthWindowMs = gmsTransportBadAuthWindowMs.coerceIn(60_000L, 60 * 60_000L),
        gmsTransportMissingAfterBadAuthMs =
            gmsTransportMissingAfterBadAuthMs.coerceIn(30_000L, 15 * 60_000L),
        gmsTransportLostMs = gmsTransportLostMs.coerceIn(2 * 60_000L, 30 * 60_000L),
        gmsTransportVerifyWaitMs = gmsTransportVerifyWaitMs.coerceIn(15_000L, 3 * 60_000L)
    )

    fun toJson(): String = JSONObject()
        .put("schema", SCHEMA)
        .put("processTargets", JSONArray(processTargets))
        .put("packageTargets", JSONArray(packageTargets))
        .put("pollIntervalMs", pollIntervalMs)
        .put("reassertIntervalMs", reassertIntervalMs)
        .put("tuningIntervalMs", tuningIntervalMs)
        .put("stickyUnfreeze", stickyUnfreeze)
        .put("tuneStandby", tuneStandby)
        .put("tuneAppOps", tuneAppOps)
        .put("tuneDeviceIdle", tuneDeviceIdle)
        .put("tuneNetworkPolicy", tuneNetworkPolicy)
        .put("tuneHibernation", tuneHibernation)
        .put("rootCgroupThaw", rootCgroupThaw)
        .put("gmsRecoveryEnabled", gmsRecoveryEnabled)
        .put("vendorEmergencyRecoveryEnabled", vendorEmergencyRecoveryEnabled)
        .put("gmsFreezeThreshold", gmsFreezeThreshold)
        .put("gmsFreezeWindowMs", gmsFreezeWindowMs)
        .put("gmsAutomaticCooldownMs", gmsAutomaticCooldownMs)
        .put("gmsManualCooldownMs", gmsManualCooldownMs)
        .put("gmsMaxRecoveriesPer24Hours", gmsMaxRecoveriesPer24Hours)
        .put("gmsRestartWaitMs", gmsRestartWaitMs)
        .put("gmsRestartPollMs", gmsRestartPollMs)
        .put("gmsTransportProbeIntervalMs", gmsTransportProbeIntervalMs)
        .put("gmsTransportBadAuthWindowMs", gmsTransportBadAuthWindowMs)
        .put("gmsTransportMissingAfterBadAuthMs", gmsTransportMissingAfterBadAuthMs)
        .put("gmsTransportLostMs", gmsTransportLostMs)
        .put("gmsTransportVerifyWaitMs", gmsTransportVerifyWaitMs)
        .toString()

    companion object {
        const val SCHEMA = 5

        val DEFAULT_PROCESS_TARGETS = listOf(
            "com.google.android.gms",
            "com.google.android.gms.persistent",
            "com.whatsapp",
            "com.whatsapp:account_switching",
            "com.whatsapp.w4b",
            "com.tailscale.ipn",
            "com.termux",
            "ch.protonvpn.android"
        )

        val DEFAULT_PACKAGE_TARGETS = listOf(
            "com.yubegreen.luonnotar",
            "com.google.android.gms",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.tailscale.ipn",
            "com.termux",
            "com.termux.boot",
            "ch.protonvpn.android"
        )

        private val SCHEMA_5_PROCESS_ADDITIONS = listOf("com.termux")
        private val SCHEMA_5_PACKAGE_ADDITIONS = listOf("com.termux", "com.termux.boot")

        private val SAFE_IDENTIFIER = Regex("^[A-Za-z0-9_]+(?:[.:][A-Za-z0-9_]+)+$")

        fun isSafeProcessName(value: String): Boolean =
            value.length in 3..180 && SAFE_IDENTIFIER.matches(value)

        fun isSafePackageName(value: String): Boolean =
            value.length in 3..180 && !value.contains(':') && SAFE_IDENTIFIER.matches(value)

        fun fromJson(raw: String?): GuardianEngineConfig {
            if (raw.isNullOrBlank()) return GuardianEngineConfig()
            return runCatching {
                val json = JSONObject(raw)
                val schema = json.optInt("schema", 0)
                val storedProcessTargets =
                    json.stringList("processTargets", DEFAULT_PROCESS_TARGETS)
                val storedPackageTargets =
                    json.stringList("packageTargets", DEFAULT_PACKAGE_TARGETS)
                GuardianEngineConfig(
                    processTargets = if (schema < 5) {
                        (storedProcessTargets + SCHEMA_5_PROCESS_ADDITIONS).distinct()
                    } else {
                        storedProcessTargets
                    },
                    packageTargets = if (schema < 5) {
                        (storedPackageTargets + SCHEMA_5_PACKAGE_ADDITIONS).distinct()
                    } else {
                        storedPackageTargets
                    },
                    pollIntervalMs = json.optLong("pollIntervalMs", 15_000L),
                    reassertIntervalMs = json.optLong("reassertIntervalMs", 60_000L),
                    tuningIntervalMs = json.optLong("tuningIntervalMs", 15 * 60_000L),
                    stickyUnfreeze = json.optBoolean("stickyUnfreeze", true),
                    tuneStandby = json.optBoolean("tuneStandby", true),
                    tuneAppOps = json.optBoolean("tuneAppOps", true),
                    tuneDeviceIdle = json.optBoolean("tuneDeviceIdle", true),
                    tuneNetworkPolicy = json.optBoolean("tuneNetworkPolicy", true),
                    tuneHibernation = json.optBoolean("tuneHibernation", true),
                    rootCgroupThaw = json.optBoolean("rootCgroupThaw", true),
                    gmsRecoveryEnabled = json.optBoolean("gmsRecoveryEnabled", false),
                    vendorEmergencyRecoveryEnabled =
                        json.optBoolean("vendorEmergencyRecoveryEnabled", true),
                    gmsFreezeThreshold = json.optInt("gmsFreezeThreshold", 3),
                    gmsFreezeWindowMs = json.optLong("gmsFreezeWindowMs", 10 * 60_000L),
                    gmsAutomaticCooldownMs = json.optLong("gmsAutomaticCooldownMs", 6 * 60 * 60_000L),
                    gmsManualCooldownMs = json.optLong("gmsManualCooldownMs", 2 * 60_000L),
                    gmsMaxRecoveriesPer24Hours = json.optInt("gmsMaxRecoveriesPer24Hours", 2),
                    gmsRestartWaitMs = json.optLong("gmsRestartWaitMs", 45_000L),
                    gmsRestartPollMs = json.optLong("gmsRestartPollMs", 1_000L),
                    gmsTransportProbeIntervalMs =
                        json.optLong("gmsTransportProbeIntervalMs", 30_000L),
                    gmsTransportBadAuthWindowMs =
                        json.optLong("gmsTransportBadAuthWindowMs", 10 * 60_000L),
                    gmsTransportMissingAfterBadAuthMs =
                        json.optLong("gmsTransportMissingAfterBadAuthMs", 90_000L),
                    gmsTransportLostMs =
                        json.optLong("gmsTransportLostMs", 4 * 60_000L),
                    gmsTransportVerifyWaitMs =
                        json.optLong("gmsTransportVerifyWaitMs", 60_000L)
                ).normalized()
            }.getOrElse { GuardianEngineConfig() }
        }

        private fun JSONObject.stringList(key: String, fallback: List<String>): List<String> {
            val array = optJSONArray(key) ?: return fallback
            return buildList {
                for (index in 0 until array.length()) {
                    array.optString(index)?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
    }
}
