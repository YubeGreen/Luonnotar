package com.yubegreen.luonnotar.monitor

import org.json.JSONArray
import org.json.JSONObject

enum class DiagnosticTruth {
    TRUE,
    FALSE,
    UNKNOWN
}

data class TargetUidHealthSnapshot(
    val packageName: String,
    val uid: Int,
    val installed: DiagnosticTruth,
    val frozen: DiagnosticTruth,
    val processPresent: DiagnosticTruth,
    val processState: String,
    val backgroundRestricted: DiagnosticTruth,
    val standbyBucket: String,
    val inactive: DiagnosticTruth,
    val netpolicyBlocked: DiagnosticTruth,
    val packageStopped: DiagnosticTruth,
    val packageEnabled: DiagnosticTruth,
    val packageSuspended: DiagnosticTruth,
    val notificationEnabled: DiagnosticTruth,
    val postNotificationsAllowed: DiagnosticTruth,
    val capturedWallTime: String,
    val commandSupported: DiagnosticTruth,
    val exitCode: Int,
    val outputParsed: DiagnosticTruth,
    val captureError: String
) {
    fun toTimelineMap(): Map<String, Any?> = linkedMapOf(
        "targetPackage" to packageName,
        "uid" to uid,
        "installed" to installed,
        "frozen" to frozen,
        "processPresent" to processPresent,
        "processState" to processState,
        "backgroundRestricted" to backgroundRestricted,
        "standbyBucket" to standbyBucket,
        "inactive" to inactive,
        "netpolicyBlocked" to netpolicyBlocked,
        "packageStopped" to packageStopped,
        "packageEnabled" to packageEnabled,
        "packageSuspended" to packageSuspended,
        "notificationEnabled" to notificationEnabled,
        "postNotificationsAllowed" to postNotificationsAllowed,
        "capturedWallTime" to capturedWallTime,
        "commandSupported" to commandSupported.name,
        "exitCode" to exitCode,
        "outputParsed" to outputParsed.name,
        "captureError" to captureError
    )

    fun toSanitizedJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("uid", uid)
        put("installed", installed.name)
        put("frozen", frozen.name)
        put("processPresent", processPresent.name)
        put("processState", processState)
        put("backgroundRestricted", backgroundRestricted.name)
        put("standbyBucket", standbyBucket)
        put("inactive", inactive.name)
        put("netpolicyBlocked", netpolicyBlocked.name)
        put("packageStopped", packageStopped.name)
        put("packageEnabled", packageEnabled.name)
        put("packageSuspended", packageSuspended.name)
        put("notificationEnabled", notificationEnabled.name)
        put("postNotificationsAllowed", postNotificationsAllowed.name)
        put("capturedWallTime", capturedWallTime)
        put("commandSupported", commandSupported.name)
        put("exitCode", exitCode)
        put("outputParsed", outputParsed.name)
        put("captureError", captureError)
    }

    companion object {
        private val allowedPackages = setOf(
            "com.yubegreen.luonnotar",
            "ch.protonvpn.android",
            "com.tailscale.ipn",
            "com.google.android.gms",
            "com.whatsapp",
            "com.whatsapp.w4b"
        )

        internal fun isAllowedTargetPackage(packageName: String): Boolean =
            packageName in allowedPackages

        fun parseArray(raw: String): List<TargetUidHealthSnapshot> {
            if (raw.isBlank() || raw.length > 48_000) return emptyList()
            val array = JSONArray(raw)
            return buildList {
                for (index in 0 until array.length()) {
                    val value = parse(array.optJSONObject(index) ?: continue)
                    if (value != null) add(value)
                }
            }
        }

        fun toSanitizedArray(snapshots: List<TargetUidHealthSnapshot>): String =
            JSONArray().apply {
                snapshots.forEach { put(it.toSanitizedJson()) }
            }.toString()

        private fun parse(json: JSONObject): TargetUidHealthSnapshot? {
            val packageName = json.optString("packageName").take(120)
            if (!isAllowedTargetPackage(packageName)) return null
            return TargetUidHealthSnapshot(
                packageName = packageName,
                uid = json.optInt("uid", -1),
                installed = truth(json, "installed"),
                frozen = truth(json, "frozen"),
                processPresent = truth(json, "processPresent"),
                processState = json.optString("processState").take(48),
                backgroundRestricted =
                    truth(json, "backgroundRestricted"),
                standbyBucket = json.optString("standbyBucket").take(32),
                inactive = truth(json, "inactive"),
                netpolicyBlocked =
                    truth(json, "netpolicyBlocked"),
                packageStopped =
                    truth(json, "packageStopped"),
                packageEnabled =
                    truth(json, "packageEnabled"),
                packageSuspended =
                    truth(json, "packageSuspended"),
                notificationEnabled =
                    truth(json, "notificationEnabled"),
                postNotificationsAllowed =
                    truth(json, "postNotificationsAllowed"),
                capturedWallTime =
                    json.optString("capturedWallTime").take(40),
                commandSupported = truth(json, "commandSupported"),
                exitCode = json.optInt("exitCode", -1),
                outputParsed = truth(json, "outputParsed"),
                captureError = json.optString("captureError").take(160)
            )
        }

        private fun truth(
            json: JSONObject,
            key: String
        ): DiagnosticTruth = truthValue(
            present = json.has(key) && !json.isNull(key),
            value = json.opt(key)
        )

        internal fun truthValue(
            present: Boolean,
            value: Any?
        ): DiagnosticTruth {
            if (!present) return DiagnosticTruth.UNKNOWN
            return when (value) {
                is Boolean ->
                    if (value) DiagnosticTruth.TRUE
                    else DiagnosticTruth.FALSE
                is String -> runCatching {
                    DiagnosticTruth.valueOf(value.uppercase())
                }.getOrDefault(DiagnosticTruth.UNKNOWN)
                else -> DiagnosticTruth.UNKNOWN
            }
        }
    }
}
