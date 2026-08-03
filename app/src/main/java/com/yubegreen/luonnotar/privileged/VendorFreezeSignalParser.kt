package com.yubegreen.luonnotar.privileged

/**
 * Parses freezer evidence emitted by AOSP and vendor system services.
 *
 * The parser is intentionally pure Kotlin so observed ROM log formats can be
 * regression-tested without an Android runtime. It only returns signals for
 * configured guardian targets; unrelated system noise is ignored.
 */
enum class VendorFreezeSignalKind {
    AOSP_APP_FROZEN,
    XIAOMI_GREEZER_DENIAL,
    UID_FROZEN_WAKELOCK,
    GCM_DELIVERY_CANCELLED,
    AUTOSTART_LAUNCH_DENIED
}

data class VendorFreezeSignal(
    val kind: VendorFreezeSignalKind,
    val packageName: String,
    val processName: String,
    val deliveryCritical: Boolean,
    val rawLine: String
)

object VendorFreezeSignalParser {
    private val aospFrozen = Regex(
        """am_app_frozen:\s*\[\s*\d+\s*,\s*\d+\s*,\s*([A-Za-z0-9_.:]+)\s*,"""
    )
    private val processRecord = Regex(
        """ProcessRecord\{[^}]*?\s\d+:([A-Za-z0-9_.:]+)/u\d+(?:a\d+|s\d+)?"""
    )
    private val workSourcePackage = Regex(
        """WorkSource\{(?:[^{}]*?\s)?\d+\s+([A-Za-z0-9_.:]+)(?:[}\s])"""
    )
    private val intentPackage = Regex("""\bpkg=([A-Za-z0-9_.:]+)""")
    private val launchDeniedPackage = Regex(
        """Unable to launch app\s+([A-Za-z0-9_.:]+)/\d+"""
    )

    fun parse(
        line: String,
        processTargets: List<String>,
        packageTargets: List<String>
    ): VendorFreezeSignal? {
        if (line.isBlank()) return null
        val packages = configuredPackages(processTargets, packageTargets)
        if (packages.isEmpty()) return null

        if (line.contains("am_app_frozen", ignoreCase = true)) {
            val process = aospFrozen.find(line)?.groupValues?.getOrNull(1)
                ?: processTargets.longestContainedIn(line)
                ?: return null
            val packageName = packages.packageForProcess(process) ?: return null
            return VendorFreezeSignal(
                kind = VendorFreezeSignalKind.AOSP_APP_FROZEN,
                packageName = packageName,
                processName = process,
                deliveryCritical = false,
                rawLine = line
            )
        }

        if (line.contains("Greezer Denial", ignoreCase = true)) {
            val process = processRecord.find(line)?.groupValues?.getOrNull(1)
                ?: processTargets.longestContainedIn(line)
                ?: return null
            val packageName = packages.packageForProcess(process) ?: return null
            val c2dm = line.contains("com.google.android.c2dm.intent.RECEIVE", ignoreCase = true)
            return VendorFreezeSignal(
                kind = VendorFreezeSignalKind.XIAOMI_GREEZER_DENIAL,
                packageName = packageName,
                processName = process,
                deliveryCritical = c2dm,
                rawLine = line
            )
        }

        if (line.contains("reason: UidFrozen", ignoreCase = true)) {
            val process = workSourcePackage.find(line)?.groupValues?.getOrNull(1)
                ?: processTargets.longestContainedIn(line)
                ?: return null
            val packageName = packages.packageForProcess(process) ?: return null
            return VendorFreezeSignal(
                kind = VendorFreezeSignalKind.UID_FROZEN_WAKELOCK,
                packageName = packageName,
                processName = process,
                deliveryCritical = line.contains("GOOGLE_C2DM", ignoreCase = true),
                rawLine = line
            )
        }

        if (
            line.contains("broadcast intent callback", ignoreCase = true) &&
            line.contains("result=CANCELLED", ignoreCase = true)
        ) {
            val process = intentPackage.find(line)?.groupValues?.getOrNull(1)
                ?: processTargets.longestContainedIn(line)
                ?: return null
            val packageName = packages.packageForProcess(process) ?: return null
            return VendorFreezeSignal(
                kind = VendorFreezeSignalKind.GCM_DELIVERY_CANCELLED,
                packageName = packageName,
                processName = process,
                deliveryCritical = line.contains("c2dm", ignoreCase = true),
                rawLine = line
            )
        }

        if (
            line.contains("Unable to launch app", ignoreCase = true) &&
            line.contains("not permitted to", ignoreCase = true)
        ) {
            val process = launchDeniedPackage.find(line)?.groupValues?.getOrNull(1)
                ?: return null
            val packageName = packages.packageForProcess(process) ?: return null
            return VendorFreezeSignal(
                kind = VendorFreezeSignalKind.AUTOSTART_LAUNCH_DENIED,
                packageName = packageName,
                processName = process,
                deliveryCritical = line.contains("c2dm", ignoreCase = true),
                rawLine = line
            )
        }

        return null
    }

    fun packageForProcess(
        processName: String,
        processTargets: List<String>,
        packageTargets: List<String>
    ): String? = configuredPackages(processTargets, packageTargets).packageForProcess(processName)

    private fun configuredPackages(
        processTargets: List<String>,
        packageTargets: List<String>
    ): List<String> {
        val explicit = packageTargets
            .filter { GuardianEngineConfig.isSafePackageName(it) }
            .distinct()
            .sortedByDescending { it.length }
        val inferred = processTargets.mapNotNull { processName ->
            explicit.firstOrNull { packageName ->
                processName == packageName ||
                    processName.startsWith("$packageName:") ||
                    processName.startsWith("$packageName.")
            } ?: processName.substringBefore(':')
                .takeIf { GuardianEngineConfig.isSafePackageName(it) }
        }
        return (explicit + inferred).distinct().sortedByDescending { it.length }
    }

    private fun List<String>.packageForProcess(processName: String): String? =
        firstOrNull { packageName ->
            processName == packageName ||
                processName.startsWith("$packageName:") ||
                processName.startsWith("$packageName.")
        }

    private fun List<String>.longestContainedIn(line: String): String? =
        sortedByDescending { it.length }.firstOrNull { target ->
            line.contains(target, ignoreCase = false)
        }
}

object VendorFreezeRecoveryPolicy {
    const val GENERAL_HOLD_MS = 5 * 60_000L
    const val AOSP_HOLD_MS = 5 * 60_000L
    const val DELIVERY_CRITICAL_HOLD_MS = 15 * 60_000L
    const val RELAPSE_WINDOW_MS = 15_000L
    const val SIGNAL_DEBOUNCE_MS = 750L
    const val EXHAUSTED_COOLDOWN_MS = 5 * 60_000L

    val REASSERT_DELAYS_MS = longArrayOf(0L, 1_000L, 3_000L, 10_000L, 30_000L)

    fun holdDurationMs(signal: VendorFreezeSignal): Long = when {
        signal.deliveryCritical -> DELIVERY_CRITICAL_HOLD_MS
        signal.kind == VendorFreezeSignalKind.AOSP_APP_FROZEN -> AOSP_HOLD_MS
        else -> GENERAL_HOLD_MS
    }
}
