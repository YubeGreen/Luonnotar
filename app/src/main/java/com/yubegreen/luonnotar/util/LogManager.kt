package com.yubegreen.luonnotar.util

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.yubegreen.luonnotar.BuildConfig
import com.yubegreen.luonnotar.service.GuardianStatusClient
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogManager {
    const val TAG = "Luonnotar"
    private const val MAX_FILE_BYTES = 1_048_576L
    private const val MAX_FILES = 4
    private const val MAX_EXPORTS = 3
    private val lock = Any()
    private val currentBootId: String by lazy { readBootIdFromSystem() }
    private val bootToken: String by lazy {
        currentBootId.filter(Char::isLetterOrDigit).take(12).ifBlank { "unknownboot" }
    }

    fun initialize(context: Context) {
        synchronized(lock) {
            withCrossProcessLogLock(context) {
                logDir(context).mkdirs()
                archiveStaleCurrentFiles(context)
                trim(context)
            }
        }
    }

    fun event(
        context: Context,
        type: String,
        details: Map<String, Any?> = emptyMap()
    ) {
        runCatching {
            val record = baseRecord(context, type)
            details.forEach { (key, value) -> record.put(key, value ?: JSONObject.NULL) }
            append(context, record.toString())
            Log.i(TAG, "$type $details")
        }.onFailure { Log.e(TAG, "log failure: $type", it) }
    }

    fun timeline(
        context: Context,
        timelineEvent: String,
        details: Map<String, Any?> = emptyMap()
    ) {
        val status = readStatusSnapshot(context, queryProvider = true)
        val power = context.getSystemService(PowerManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity.activeNetwork
        val nowElapsed = SystemClock.elapsedRealtime()
        val lastAttemptElapsed = status?.getLong(
            LuonnotarPreferences.KEY_LAST_ATTEMPT_ELAPSED,
            0L
        ) ?: 0L
        val snapshot = GuardianTimelineSnapshot(
            wallTime = Instant.now().toString(),
            elapsedRealtime = nowElapsed,
            screenInteractive = power.isInteractive,
            deviceIdleMode = power.isDeviceIdleMode,
            powerSaveMode = power.isPowerSaveMode,
            wakeLockHeld = status?.getBoolean(
                LuonnotarPreferences.KEY_WAKE_LOCK,
                false
            ) == true,
            wifiLockHeld = status?.getBoolean(
                LuonnotarPreferences.KEY_WIFI_LOCK,
                false
            ) == true,
            networkHandle = status?.getLong(
                LuonnotarPreferences.KEY_NETWORK_HANDLE,
                activeNetwork?.networkHandle ?: -1L
            ) ?: (activeNetwork?.networkHandle ?: -1L),
            vpnPresent = status?.getBoolean(
                LuonnotarPreferences.KEY_VPN,
                false
            ) == true,
            validated = status?.getBoolean(
                LuonnotarPreferences.KEY_VALIDATED,
                false
            ) == true,
            underlay = status?.getString(
                LuonnotarPreferences.KEY_TRANSPORT,
                "UNDERLAY_UNKNOWN"
            ) ?: "UNDERLAY_UNKNOWN",
            probeInFlight = status?.getBoolean(
                LuonnotarPreferences.KEY_PROBE_IN_FLIGHT,
                false
            ) == true,
            lastProbeAgeMs = if (
                lastAttemptElapsed > 0L &&
                lastAttemptElapsed <= nowElapsed
            ) {
                nowElapsed - lastAttemptElapsed
            } else {
                -1L
            },
            lastProbeRttMs = status?.getLong(
                LuonnotarPreferences.KEY_LAST_ATTEMPT_RTT,
                -1L
            ) ?: -1L,
            timerDriftMs = status?.getLong(
                LuonnotarPreferences.KEY_LAST_TIMER_DRIFT,
                0L
            ) ?: 0L,
            serviceGeneration = status?.getLong(
                LuonnotarPreferences.KEY_SERVICE_GENERATION,
                0L
            ) ?: 0L
        )
        event(
            context,
            "guardian_timeline",
            snapshot.toMap() +
                mapOf("timelineEvent" to timelineEvent) +
                details
        )
    }

    fun exportZip(context: Context): File {
        val guardianSnapshot = GuardianStatusClient.status(context)
        synchronized(lock) {
            return withCrossProcessLogLock(context) {
                val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
                exportDir.listFiles()
                    ?.filter { it.isFile && it.extension == "zip" }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(MAX_EXPORTS - 1)
                    ?.forEach { it.delete() }
                val output = File(
                    exportDir,
                    "luonnotar-diagnostics-${System.currentTimeMillis()}.zip"
                )
                ZipOutputStream(FileOutputStream(output)).use { zip ->
                    logDir(context).listFiles()
                        ?.sortedBy { it.name }
                        ?.forEachIndexed { index, file ->
                        zip.putNextEntry(ZipEntry("logs/events-$index.jsonl"))
                        file.useLines { lines ->
                            lines.forEach { line ->
                                zip.write(
                                    (sanitizeLogLine(line) + "\n").toByteArray()
                                )
                            }
                        }
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry("device-summary.json"))
                    zip.write(
                        baseRecord(
                            context,
                            "export_summary",
                            guardianSnapshot
                        ).toString(2).toByteArray()
                    )
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("diagnostic-manifest.json"))
                    zip.write(
                        JSONObject().apply {
                            put("formatVersion", 3)
                            put("timelineIncluded", true)
                            put("configurationIncluded", true)
                            put("experimentSessionIncluded", true)
                            put("adbAdviceIncluded", true)
                            put(
                                "excluded",
                                org.json.JSONArray(
                                    listOf(
                                        "ordinary_chat_text",
                                        "phone_numbers",
                                        "contact_names",
                                        "fcm_tokens",
                                        "vpn_credentials",
                                        "keystores",
                                        "pass" + "words"
                                    )
                                )
                            )
                        }.toString(2).toByteArray()
                    )
                    zip.closeEntry()
                }
                output
            }
        }
    }

    private fun baseRecord(
        context: Context,
        type: String,
        guardianSnapshot: Bundle? = null
    ): JSONObject {
        val power = context.getSystemService(PowerManager::class.java)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork
        val caps = network?.let(cm::getNetworkCapabilities)
        return JSONObject().apply {
            put("wallTime", Instant.now().toString())
            put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            put("uptimeMs", SystemClock.uptimeMillis())
            put("bootIdAnonymous", anonymousBootId(currentBootId))
            put("pid", Process.myPid())
            put("event", type)
            put("appVersionName", BuildConfig.VERSION_NAME)
            put("appVersionCode", BuildConfig.VERSION_CODE)
            put("screenInteractive", power.isInteractive)
            put("deviceIdle", power.isDeviceIdleMode)
            put("deviceIdleMode", power.isDeviceIdleMode)
            put("powerSaveMode", power.isPowerSaveMode)
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("buildDisplay", Build.DISPLAY)
            put("buildIncremental", Build.VERSION.INCREMENTAL)
            put("android", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put(
                "batteryOptimizationExempt",
                power.isIgnoringBatteryOptimizations(context.packageName)
            )
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            put(
                "notificationListenerAuthorized",
                enabledListeners.split(':').any {
                    it.substringBefore('/').equals(
                        context.packageName,
                        ignoreCase = true
                    )
                }
            )
            put("networkHandle", network?.networkHandle ?: -1)
            put("vpn", caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
            put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            val status = guardianSnapshot ?: readStatusSnapshot(context)
            put("wakeLockHeld", status?.getBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, false))
            put("wifiLockHeld", status?.getBoolean(LuonnotarPreferences.KEY_WIFI_LOCK, false))
            put(
                "screenOffCpuGuardEnabled",
                status?.getBoolean(
                    LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD,
                    false
                ) == true
            )
            put(
                "continuousWakeLockHeld",
                status?.getBoolean(
                    LuonnotarPreferences.KEY_CONTINUOUS_WAKE_LOCK,
                    false
                ) == true
            )
            put(
                "gmsPreventivePulseCount",
                status?.getInt(
                    LuonnotarPreferences.KEY_GMS_PREVENTIVE_PULSE_COUNT,
                    0
                ) ?: 0
            )
            put(
                "gmsPreventivePulseLastAttemptElapsed",
                status?.getLong(
                    LuonnotarPreferences
                        .KEY_GMS_PREVENTIVE_PULSE_LAST_ATTEMPT_ELAPSED,
                    0L
                ) ?: 0L
            )
            put(
                "gmsPreventivePulseLastReason",
                status?.getString(
                    LuonnotarPreferences.KEY_GMS_PREVENTIVE_PULSE_LAST_REASON,
                    ""
                ) ?: ""
            )
            put("processSequence", status?.getLong(LuonnotarPreferences.KEY_PROCESS_SEQUENCE, 0))
            put("guardianEnabled", status?.getBoolean(LuonnotarPreferences.KEY_ENABLED, false))
            put("guardianPaused", status?.getBoolean(LuonnotarPreferences.KEY_PAUSED, false))
            put("guardianState", status?.getString(LuonnotarPreferences.KEY_STATE, "UNKNOWN"))
            put(
                "experimentSessionActive",
                status?.getBoolean(
                    LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ACTIVE,
                    false
                ) == true
            )
            put(
                "experimentSessionId",
                status?.getString(
                    LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ID,
                    ""
                ).orEmpty()
            )
            put(
                "experimentSessionName",
                status?.getString(
                    LuonnotarPreferences.KEY_EXPERIMENT_SESSION_NAME,
                    ""
                ).orEmpty()
            )
            val experimentStartedElapsed = status?.getLong(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_STARTED_ELAPSED,
                0L
            ) ?: 0L
            put(
                "experimentSessionAgeMs",
                if (
                    status?.getBoolean(
                        LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ACTIVE,
                        false
                    ) == true &&
                    experimentStartedElapsed > 0L &&
                    experimentStartedElapsed <= SystemClock.elapsedRealtime()
                ) {
                    SystemClock.elapsedRealtime() - experimentStartedElapsed
                } else {
                    -1L
                }
            )
            put("guardianPid", status?.getInt(LuonnotarPreferences.KEY_PID, 0))
            put("heartbeatElapsed", status?.getLong(LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED, 0))
            put("defaultVpn", status?.getBoolean(LuonnotarPreferences.KEY_VPN, false))
            put("defaultValidated", status?.getBoolean(LuonnotarPreferences.KEY_VALIDATED, false))
            put(
                "vpnProviderPackage",
                status?.getString(
                    LuonnotarPreferences.KEY_VPN_PROVIDER_PACKAGE,
                    ""
                )
            )
            put(
                "vpnInternetRouted",
                status?.getBoolean(
                    LuonnotarPreferences.KEY_VPN_INTERNET_ROUTED,
                    false
                )
            )
            put(
                "vpnRouteState",
                status?.getString(
                    LuonnotarPreferences.KEY_VPN_ROUTE_STATE,
                    "UNKNOWN"
                )
            )
            put(
                "vpnSessionFingerprint",
                status?.getString(
                    LuonnotarPreferences.KEY_VPN_SESSION_FINGERPRINT,
                    ""
                )
            )
            put(
                "vpnSessionGeneration",
                status?.getLong(
                    LuonnotarPreferences.KEY_VPN_SESSION_GENERATION,
                    0L
                )
            )
            put(
                "vpnSessionHealth",
                status?.getString(
                    LuonnotarPreferences.KEY_VPN_SESSION_HEALTH,
                    "UNKNOWN"
                )
            )
            put(
                "vpnDnsHealth",
                status?.getString(
                    LuonnotarPreferences.KEY_VPN_DNS_HEALTH,
                    "UNKNOWN"
                )
            )
            put(
                "vpnHttpsHealth",
                status?.getString(
                    LuonnotarPreferences.KEY_VPN_HTTPS_HEALTH,
                    "UNKNOWN"
                )
            )
            put(
                "fcmHealth",
                status?.getString(
                    LuonnotarPreferences.KEY_FCM_HEALTH,
                    "UNKNOWN_NOT_MEASURED"
                )
            )
            put(
                "mtalkResultSummary",
                status?.getString(
                    LuonnotarPreferences.KEY_MTALK_RESULT_SUMMARY,
                    ""
                )
            )
            put(
                "targetUidHealthSnapshot",
                status?.getString(
                    LuonnotarPreferences.KEY_TARGET_UID_HEALTH_SNAPSHOT,
                    ""
                )
            )
            put(
                "vpnIpv4DefaultRoute",
                status?.getBoolean(
                    LuonnotarPreferences.KEY_VPN_IPV4_DEFAULT_ROUTE,
                    false
                )
            )
            put(
                "vpnIpv6DefaultRoute",
                status?.getBoolean(
                    LuonnotarPreferences.KEY_VPN_IPV6_DEFAULT_ROUTE,
                    false
                )
            )
            put(
                "vpnBypassableKnown",
                status?.getBoolean(LuonnotarPreferences.KEY_BYPASSABLE_KNOWN, false)
            )
            put(
                "vpnBypassable",
                status?.getBoolean(LuonnotarPreferences.KEY_BYPASSABLE, true)
            )
            put(
                "underlyingTransport",
                status?.getString(LuonnotarPreferences.KEY_TRANSPORT, "UNKNOWN")
            )
            put(
                "underlaySource",
                status?.getString(
                    LuonnotarPreferences.KEY_UNDERLAY_SOURCE,
                    "unknown"
                )
            )
            put(
                "lastExplicitUnderlay",
                status?.getString(
                    LuonnotarPreferences.KEY_LAST_EXPLICIT_UNDERLAY,
                    "NONE"
                )
            )
            put(
                "underlayUnknownSinceElapsed",
                status?.getLong(
                    LuonnotarPreferences.KEY_UNDERLAY_UNKNOWN_SINCE,
                    0L
                )
            )
            put(
                "aggressiveVivoMode",
                status?.getBoolean(
                    LuonnotarPreferences.KEY_AGGRESSIVE_VIVO_MODE,
                    false
                )
            )
            put(
                "probeInFlight",
                status?.getBoolean(
                    LuonnotarPreferences.KEY_PROBE_IN_FLIGHT,
                    false
                )
            )
            put(
                "notificationListenerConnected",
                status?.getBoolean(
                    LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_CONNECTED,
                    false
                )
            )
            put("controlledPushTest", JSONObject().apply {
                put(
                    "sequence",
                    status?.getLong(
                        LuonnotarPreferences.KEY_PUSH_TEST_LAST_SEQUENCE,
                        0L
                    )
                )
                put(
                    "senderEpochMs",
                    status?.getLong(
                        LuonnotarPreferences
                            .KEY_PUSH_TEST_LAST_SENDER_EPOCH_MS,
                        0L
                    )
                )
                put(
                    "seenWall",
                    status?.getLong(
                        LuonnotarPreferences.KEY_PUSH_TEST_LAST_SEEN_WALL,
                        0L
                    )
                )
                put(
                    "seenElapsed",
                    status?.getLong(
                        LuonnotarPreferences.KEY_PUSH_TEST_LAST_SEEN_ELAPSED,
                        0L
                    )
                )
                put(
                    "approximateDelayMs",
                    status?.getLong(
                        LuonnotarPreferences.KEY_PUSH_TEST_LAST_DELAY_MS,
                        -1L
                    )
                )
                put(
                    "packageName",
                    status?.getString(
                        LuonnotarPreferences.KEY_PUSH_TEST_LAST_PACKAGE,
                        ""
                    )
                )
            })
            put(
                "lastTimerDriftMs",
                status?.getLong(
                    LuonnotarPreferences.KEY_LAST_TIMER_DRIFT,
                    0L
                )
            )
            put(
                "lastAttemptRttMs",
                status?.getLong(LuonnotarPreferences.KEY_LAST_ATTEMPT_RTT, -1L)
            )
            put(
                "lastSuccessfulRttMs",
                status?.getLong(LuonnotarPreferences.KEY_LAST_SUCCESS_RTT, -1L)
            )
            put("lastHttpCode", status?.getInt(LuonnotarPreferences.KEY_LAST_HTTP_CODE, -1))
            put(
                "lastProbeElapsed",
                status?.getLong(
                    LuonnotarPreferences.KEY_LAST_ATTEMPT_ELAPSED,
                    0L
                )
            )
            put(
                "lastSuccessfulProbeElapsed",
                status?.getLong(
                    LuonnotarPreferences.KEY_LAST_SUCCESS_ELAPSED,
                    0L
                )
            )
            put(
                "lastSuccessfulProbeNetworkHandle",
                status?.getLong(
                    LuonnotarPreferences.KEY_LAST_SUCCESS_NETWORK_HANDLE,
                    -1L
                )
            )
            put(
                "consecutiveFailures",
                status?.getInt(LuonnotarPreferences.KEY_CONSECUTIVE_FAILURES, 0)
            )
            put(
                "lastError",
                status?.getString(LuonnotarPreferences.KEY_LAST_ERROR, "")
            )
            put(
                "lastStartReason",
                status?.getString(LuonnotarPreferences.KEY_LAST_START_REASON, "")
            )
            put(
                "lastServiceExit",
                status?.getString(LuonnotarPreferences.KEY_LAST_SERVICE_EXIT, "")
            )
            put("recoveryFailures", JSONObject().apply {
                put("service", status?.getString(
                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE,
                    ""
                ))
                put("alarm", status?.getString(
                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM,
                    ""
                ))
                put("notification", status?.getString(
                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_NOTIFICATION,
                    ""
                ))
                put("boot", status?.getString(
                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_BOOT,
                    ""
                ))
            })
            put(
                "recoveryAlarmExact",
                status?.getBoolean(LuonnotarPreferences.KEY_ALARM_EXACT, false)
            )
            put(
                "recoveryAlarmInsurance",
                status?.getBoolean(LuonnotarPreferences.KEY_ALARM_INSURANCE, false)
            )
            put(
                "adbVerificationAdvice",
                "Use the in-app model-specific ADB guide; imported routing " +
                    "evidence is diagnostic and does not verify the private " +
                    "GMS or WhatsApp FCM socket."
            )
        }
    }

    private fun sanitizeLogLine(line: String): String = runCatching {
        val record = JSONObject(line)
        if (record.has("bootId")) {
            val rawBootId = record.optString("bootId", "")
            record.remove("bootId")
            record.put("bootIdAnonymous", anonymousBootId(rawBootId))
        }
        record.remove("fcmToken")
        record.remove("token")
        record.remove("vpnCredential")
        record.remove("pass" + "word")
        record.toString()
    }.getOrElse {
        JSONObject().apply {
            put("event", "malformed_log_entry_omitted")
            put("reason", it.javaClass.simpleName)
        }.toString()
    }

    private fun anonymousBootId(bootId: String): String {
        if (bootId.isBlank() || bootId == "unavailable") return "unavailable"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bootId.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun append(context: Context, line: String) {
        synchronized(lock) {
            withCrossProcessLogLock(context) {
                val dir = logDir(context).apply { mkdirs() }
                val current = File(dir, currentLogFileName())
                if (
                    current.exists() &&
                    current.length() + line.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES
                ) {
                    val archive = File(
                        dir,
                        "events-$bootToken-${Process.myPid()}-${System.currentTimeMillis()}.jsonl"
                    )
                    if (!current.renameTo(archive)) {
                        current.copyTo(archive, overwrite = true)
                        current.delete()
                    }
                }
                current.appendText(line + "\n")
                trim(context)
            }
        }
    }

    private inline fun <T> withCrossProcessLogLock(
        context: Context,
        block: () -> T
    ): T {
        val lockFile = File(
            context.createDeviceProtectedStorageContext().filesDir,
            "luonnotar-logs.lock"
        )
        lockFile.parentFile?.mkdirs()
        return RandomAccessFile(lockFile, "rw").use { randomAccess ->
            randomAccess.channel.use { channel ->
                channel.lock().use { block() }
            }
        }
    }

    private fun trim(context: Context) {
        logDir(context).listFiles()
            ?.filter { it.isFile && !it.name.endsWith("-current.jsonl") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_FILES)
            ?.forEach { it.delete() }
    }

    private fun archiveStaleCurrentFiles(context: Context) {
        val currentPid = Process.myPid()
        val currentName = currentLogFileName()
        logDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith("-current.jsonl") }
            ?.forEach { file ->
                if (file.name == currentName) return@forEach
                val pid = file.name
                    .removePrefix("events-")
                    .removeSuffix("-current.jsonl")
                    .substringAfterLast('-')
                    .toIntOrNull() ?: return@forEach
                if (pid != currentPid && isOwnedProcessAlive(context, pid)) return@forEach
                val archive = File(
                    file.parentFile,
                    "${file.name.removeSuffix("-current.jsonl")}-${
                        file.lastModified().coerceAtLeast(1L)
                    }.jsonl"
                )
                if (!file.renameTo(archive)) {
                    file.copyTo(archive, overwrite = true)
                    file.delete()
                }
            }
    }

    private fun logDir(context: Context) =
        File(context.createDeviceProtectedStorageContext().filesDir, "logs")

    private fun currentLogFileName() =
        "events-$bootToken-${Process.myPid()}-current.jsonl"

    private fun readBootIdFromSystem(): String =
        runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }
            .getOrDefault("unavailable")

    private fun isOwnedProcessAlive(context: Context, pid: Int): Boolean {
        val processDir = File("/proc/$pid")
        if (!processDir.exists()) return false
        val processName = runCatching {
            File(processDir, "cmdline").readText().trimEnd('\u0000')
        }.getOrDefault("")
        return processName == context.packageName ||
            processName.startsWith("${context.packageName}:")
    }

    private fun isKeeperProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            runCatching {
                File("/proc/self/cmdline").readText().trimEnd('\u0000')
            }.getOrDefault("")
        }
        return processName.endsWith(":keeper")
    }

    private fun readStatusSnapshot(
        context: Context,
        queryProvider: Boolean = false
    ): Bundle? {
        if (!isKeeperProcess()) {
            return if (queryProvider) GuardianStatusClient.status(context) else null
        }
        val prefs = LuonnotarPreferences.deviceProtected(context)
        return Bundle().apply {
            prefs.all.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                }
            }
        }
    }
}
