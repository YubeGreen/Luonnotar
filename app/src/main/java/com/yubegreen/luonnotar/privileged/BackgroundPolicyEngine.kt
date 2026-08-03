package com.yubegreen.luonnotar.privileged

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

internal class BackgroundPolicyEngine(
    private val runner: GuardianCommandRunner
) {
    fun apply(
        config: GuardianEngineConfig,
        requestJson: String? = null
    ): BackgroundPolicyReport {
        val request = runCatching { JSONObject(requestJson.orEmpty()) }.getOrDefault(JSONObject())
        val source = request.optString("source", "engine_tune").take(80)
        val requestedPackages = request.optJSONArray("packages")?.safePackageList().orEmpty()
        val packages = (requestedPackages.ifEmpty { config.packageTargets })
            .map(String::trim)
            .filter(GuardianEngineConfig::isSafePackageName)
            .distinct()
        val device = BackgroundPolicyVendorDetector.detect(readProperties())
        val results = packages.map { applyToPackage(it, device.family, config) }
        val hasConfirmedPolicyFailure = results.any { target ->
            target.installed && target.capabilities.any { capability ->
                capability.state == BackgroundPolicyCapabilityState.FAILED
            }
        }
        return BackgroundPolicyReport(
            source = source,
            createdElapsed = SystemClock.elapsedRealtime(),
            device = device,
            targets = results,
            requiresOemUserAction =
                BackgroundPolicyVendorDetector.requiresPrivateLayerConfirmation(device.family) &&
                    hasConfirmedPolicyFailure,
            oemGuidance = BackgroundPolicyVendorDetector.guidance(device.family),
            commandsAttempted = results.sumOf { it.commandsAttempted },
            commandsSucceeded = results.sumOf { it.commandsSucceeded }
        )
    }

    private fun applyToPackage(
        packageName: String,
        vendorFamily: BackgroundPolicyVendorFamily,
        config: GuardianEngineConfig
    ): BackgroundPolicyTargetResult {
        if (!packageInstalled(packageName)) {
            return BackgroundPolicyTargetResult(
                packageName = packageName,
                installed = false,
                fullyVerified = false,
                commandsAttempted = 0,
                commandsSucceeded = 0,
                capabilities = listOf(
                    BackgroundPolicyCapabilityResult(
                        name = "package",
                        supported = true,
                        applied = false,
                        verified = false,
                        detail = "not_installed"
                    )
                )
            )
        }

        val counter = CommandCounter()
        val capabilities = mutableListOf<BackgroundPolicyCapabilityResult>()

        val unstopApply = counter.run(
            "cmd", "package", "unstop", "--user", "0", packageName
        )
        val unstopRead = runner.run(
            "dumpsys", "package", packageName,
            timeoutMs = VERIFY_TIMEOUT_MS
        )
        capabilities += BackgroundPolicyCapabilityResult(
            name = "package_unstopped",
            supported = !unsupported(unstopApply) && unstopRead.success,
            applied = unstopApply.success,
            verified = BackgroundPolicyOutputParser.packageStoppedFalse(unstopRead.stdout),
            detail = details(unstopApply, unstopRead)
        )

        if (config.tuneStandby) {
            val inactiveSet = counter.run(
                "am", "set-inactive", "--user", "0", packageName, "false"
            )
            val bucketSet = counter.run(
                "am", "set-standby-bucket", "--user", "0", packageName, "active"
            )
            val inactiveRead = runner.run(
                "am", "get-inactive", "--user", "0", packageName,
                timeoutMs = VERIFY_TIMEOUT_MS
            )
            val bucketRead = runner.run(
                "am", "get-standby-bucket", packageName,
                timeoutMs = VERIFY_TIMEOUT_MS
            )
            capabilities += BackgroundPolicyCapabilityResult(
                name = "standby_active",
                supported = bucketRead.exitCode != UNKNOWN_COMMAND_EXIT,
                applied = inactiveSet.success && bucketSet.success,
                verified = BackgroundPolicyOutputParser.inactiveFalse(inactiveRead.stdout) &&
                    BackgroundPolicyOutputParser.standbyBucketActive(bucketRead.stdout),
                detail = details(inactiveSet, bucketSet, inactiveRead, bucketRead)
            )

        } else {
            capabilities += BackgroundPolicyCapabilityResult(
                name = "standby_active",
                supported = false,
                applied = false,
                verified = false,
                detail = "disabled_by_config"
            )
        }

        if (config.tuneDeviceIdle) {
            val idleApplyPrimary = counter.run(
                "cmd", "deviceidle", "whitelist", "+$packageName"
            )
            val idleApply = if (idleApplyPrimary.success) {
                idleApplyPrimary
            } else {
                counter.run("dumpsys", "deviceidle", "whitelist", "+$packageName")
            }
            val idleReadPrimary = runner.run(
                "cmd", "deviceidle", "whitelist",
                timeoutMs = VERIFY_TIMEOUT_MS
            )
            val idleRead = if (
                idleReadPrimary.success ||
                BackgroundPolicyOutputParser.deviceIdleContains(idleReadPrimary.stdout, packageName)
            ) {
                idleReadPrimary
            } else {
                runner.run("dumpsys", "deviceidle", "whitelist", timeoutMs = VERIFY_TIMEOUT_MS)
            }
            capabilities += BackgroundPolicyCapabilityResult(
                name = "deviceidle_whitelist",
                supported = idleRead.exitCode != UNKNOWN_COMMAND_EXIT,
                applied = idleApply.success,
                verified = BackgroundPolicyOutputParser.deviceIdleContains(idleRead.stdout, packageName),
                detail = details(idleApply, idleRead)
            )

        } else {
            capabilities += BackgroundPolicyCapabilityResult(
                name = "deviceidle_whitelist",
                supported = false,
                applied = false,
                verified = false,
                detail = "disabled_by_config"
            )
        }

        if (config.tuneAppOps) {
            CORE_APP_OPS.forEach { operation ->
                val apply = counter.run(
                    "cmd", "appops", "set", "--user", "0", packageName, operation, "allow"
                )
                val read = runner.run(
                    "cmd", "appops", "get", "--user", "0", packageName, operation,
                    timeoutMs = VERIFY_TIMEOUT_MS
                )
                capabilities += BackgroundPolicyCapabilityResult(
                    name = "appop_$operation",
                    supported = !unsupported(read),
                    applied = apply.success,
                    verified = BackgroundPolicyOutputParser.appOpAllowed(read.stdout),
                    detail = details(apply, read)
                )
            }

            BackgroundPolicyVendorDetector.symbolicOemAppOps(vendorFamily).forEach { operation ->
                val before = runner.run(
                    "cmd", "appops", "get", "--user", "0", packageName, operation,
                    timeoutMs = VERIFY_TIMEOUT_MS
                )
                if (!unsupported(before)) {
                    val apply = counter.run(
                        "cmd", "appops", "set", "--user", "0", packageName, operation, "allow"
                    )
                    val after = runner.run(
                        "cmd", "appops", "get", "--user", "0", packageName, operation,
                        timeoutMs = VERIFY_TIMEOUT_MS
                    )
                    capabilities += BackgroundPolicyCapabilityResult(
                        name = "oem_appop_$operation",
                        supported = true,
                        applied = apply.success,
                        verified = BackgroundPolicyOutputParser.appOpAllowed(after.stdout),
                        detail = details(before, apply, after)
                    )
                }
            }

            BackgroundPolicyVendorDetector.numericOemAppOps(vendorFamily).forEach { operation ->
                val code = operation.code.toString()
                val before = runner.run(
                    "cmd", "appops", "get", "--user", "0", packageName, code,
                    timeoutMs = VERIFY_TIMEOUT_MS
                )
                if (!unsupported(before)) {
                    val apply = counter.run(
                        "cmd", "appops", "set", "--user", "0", packageName, code, "allow"
                    )
                    val after = runner.run(
                        "cmd", "appops", "get", "--user", "0", packageName, code,
                        timeoutMs = VERIFY_TIMEOUT_MS
                    )
                    capabilities += BackgroundPolicyCapabilityResult(
                        name = "oem_appop_${operation.label}_${operation.code}",
                        supported = true,
                        applied = apply.success,
                        verified = BackgroundPolicyOutputParser.appOpAllowed(after.stdout),
                        detail = details(before, apply, after)
                    )
                } else {
                    capabilities += BackgroundPolicyCapabilityResult(
                        name = "oem_appop_${operation.label}_${operation.code}",
                        supported = false,
                        applied = false,
                        verified = false,
                        detail = details(before)
                    )
                }
            }

        } else {
            CORE_APP_OPS.forEach { operation ->
                capabilities += BackgroundPolicyCapabilityResult(
                    name = "appop_$operation",
                    supported = false,
                    applied = false,
                    verified = false,
                    detail = "disabled_by_config"
                )
            }
        }

        if (config.tuneHibernation) {
            val hibernationHelp = runner.run(
                "cmd", "app_hibernation", "help",
                timeoutMs = VERIFY_TIMEOUT_MS
            )
            if (hibernationHelp.success && hibernationHelp.stdout.contains("set-state")) {
                val applyPrimary = counter.run(
                    "cmd", "app_hibernation", "set-state",
                    "--user", "0", packageName, "false"
                )
                val apply = if (applyPrimary.success && !looksLikeCommandHelp(applyPrimary)) {
                    applyPrimary
                } else {
                    counter.run(
                        "cmd", "app_hibernation", "set-state",
                        packageName, "false", "--user", "0"
                    )
                }
                val reads = listOf(
                    runner.run(
                        "cmd", "app_hibernation", "get-state",
                        "--user", "0", packageName,
                        timeoutMs = VERIFY_TIMEOUT_MS
                    ),
                    runner.run(
                        "cmd", "app_hibernation", "get-state",
                        packageName, "--user", "0",
                        timeoutMs = VERIFY_TIMEOUT_MS
                    )
                )
                val read = reads.firstOrNull { result ->
                    result.success && !looksLikeCommandHelp(result) &&
                        BackgroundPolicyOutputParser.hibernationDisabled(result.stdout)
                } ?: reads.first()
                val applyAccepted = apply.success && !looksLikeCommandHelp(apply)
                capabilities += BackgroundPolicyCapabilityResult(
                    name = "hibernation_disabled",
                    supported = !unsupported(apply) && !unsupported(read),
                    applied = applyAccepted,
                    verified = BackgroundPolicyOutputParser.hibernationDisabled(read.stdout),
                    detail = details(hibernationHelp, applyPrimary, apply, *reads.toTypedArray())
                )
            } else {
                capabilities += BackgroundPolicyCapabilityResult(
                    name = "hibernation_disabled",
                    supported = false,
                    applied = false,
                    verified = false,
                    detail = hibernationHelp.summary()
                )
            }

        } else {
            capabilities += BackgroundPolicyCapabilityResult(
                name = "hibernation_disabled",
                supported = false,
                applied = false,
                verified = false,
                detail = "disabled_by_config"
            )
        }

        if (config.tuneNetworkPolicy) {
            val uid = packageUid(packageName)
            if (uid != null) {
                val apply = counter.run(
                    "cmd", "netpolicy", "add", "restrict-background-whitelist", uid.toString()
                )
                val read = runner.run(
                    "cmd", "netpolicy", "list", "restrict-background-whitelist",
                    timeoutMs = VERIFY_TIMEOUT_MS
                )
                capabilities += BackgroundPolicyCapabilityResult(
                    name = "netpolicy_whitelist",
                    supported = !unsupported(read),
                    applied = apply.success,
                    verified = BackgroundPolicyOutputParser.netPolicyContainsUid(read.stdout, uid),
                    detail = details(apply, read)
                )
            } else {
                capabilities += BackgroundPolicyCapabilityResult(
                    name = "netpolicy_whitelist",
                    supported = false,
                    applied = false,
                    verified = false,
                    detail = "uid_unavailable"
                )
            }

        } else {
            capabilities += BackgroundPolicyCapabilityResult(
                name = "netpolicy_whitelist",
                supported = false,
                applied = false,
                verified = false,
                detail = "disabled_by_config"
            )
        }

        val requiredNames = REQUIRED_CAPABILITIES
        val fullyVerified = requiredNames.all { name ->
            capabilities.firstOrNull { it.name == name }?.policySatisfied == true
        }
        return BackgroundPolicyTargetResult(
            packageName = packageName,
            installed = true,
            fullyVerified = fullyVerified,
            commandsAttempted = counter.attempted,
            commandsSucceeded = counter.succeeded,
            capabilities = capabilities
        )
    }

    private fun readProperties(): Map<String, String> {
        val all = runner.run("getprop", timeoutMs = VERIFY_TIMEOUT_MS)
        if (all.success) {
            val parsed = linkedMapOf<String, String>()
            GETPROP_LINE.findAll(all.stdout).forEach { match ->
                parsed[match.groupValues[1]] = match.groupValues[2]
            }
            if (parsed.isNotEmpty()) return parsed
        }
        return PROPERTY_KEYS.associateWith { key ->
            runner.run("getprop", key, timeoutMs = VERIFY_TIMEOUT_MS).stdout.trim()
        }
    }

    private fun packageInstalled(packageName: String): Boolean {
        val modern = runner.run(
            "cmd", "package", "path", "--user", "0", packageName,
            timeoutMs = VERIFY_TIMEOUT_MS
        )
        if (modern.success && modern.stdout.lineSequence().any { it.startsWith("package:") }) {
            return true
        }
        val fallback = runner.run("pm", "path", packageName, timeoutMs = VERIFY_TIMEOUT_MS)
        return fallback.success && fallback.stdout.lineSequence().any { it.startsWith("package:") }
    }

    private fun packageUid(packageName: String): Int? {
        val result = runner.run(
            "cmd", "package", "list", "packages", "-U", "--user", "0", packageName,
            timeoutMs = VERIFY_TIMEOUT_MS
        )
        val exact = result.stdout.lineSequence().firstOrNull { line ->
            line.startsWith("package:$packageName ") || line == "package:$packageName"
        }
        Regex("uid:(\\d+)").find(exact.orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        val fallback = runner.run("dumpsys", "package", packageName, timeoutMs = VERIFY_TIMEOUT_MS)
        return Regex("(?:^|\\s)userId=(\\d+)(?:\\s|$)")
            .find(fallback.stdout)
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun looksLikeCommandHelp(result: GuardianCommandResult): Boolean {
        val text = "${result.stdout}\n${result.stderr}".lowercase()
        return text.contains("app hibernation commands:") ||
            (text.contains("set-state") && text.contains("get-state") && text.contains("usage"))
    }

    private fun unsupported(result: GuardianCommandResult): Boolean {
        val text = "${result.stdout}\n${result.stderr}".lowercase()
        return result.exitCode == UNKNOWN_COMMAND_EXIT ||
            text.contains("unknown command") ||
            text.contains("unknown operation") ||
            text.contains("not found")
    }

    private fun details(vararg results: GuardianCommandResult): String =
        results.joinToString(" | ") { result ->
            val command = result.command.joinToString(" ").take(180)
            "$command => ${if (result.success) "ok" else result.summary(120)}"
        }.take(600)

    private inner class CommandCounter {
        var attempted: Int = 0
            private set
        var succeeded: Int = 0
            private set

        fun run(vararg command: String): GuardianCommandResult {
            attempted += 1
            val result = runner.run(command.toList(), timeoutMs = APPLY_TIMEOUT_MS)
            if (result.success) succeeded += 1
            return result
        }
    }

    private fun JSONArray.safePackageList(): List<String> = buildList {
        for (index in 0 until length()) {
            optString(index)
                .trim()
                .takeIf(GuardianEngineConfig::isSafePackageName)
                ?.let(::add)
        }
    }

    companion object {
        private const val APPLY_TIMEOUT_MS = 5_000L
        private const val VERIFY_TIMEOUT_MS = 4_000L
        private const val UNKNOWN_COMMAND_EXIT = 127
        private val CORE_APP_OPS = listOf(
            "RUN_IN_BACKGROUND",
            "RUN_ANY_IN_BACKGROUND",
            "WAKE_LOCK",
            "START_FOREGROUND"
        )
        private val REQUIRED_CAPABILITIES = setOf(
            "standby_active",
            "deviceidle_whitelist",
            "appop_RUN_IN_BACKGROUND",
            "appop_RUN_ANY_IN_BACKGROUND"
        )
        private val PROPERTY_KEYS = listOf(
            "ro.product.manufacturer",
            "ro.product.brand",
            "ro.product.vendor.brand",
            "ro.product.system.brand",
            "ro.product.model",
            "ro.product.name",
            "ro.build.version.sdk",
            "ro.build.version.release",
            "ro.miui.ui.version.name",
            "ro.mi.os.version.name",
            "ro.mi.os.version.incremental",
            "ro.vivo.os.version",
            "ro.vivo.os.name",
            "ro.build.version.opporom",
            "ro.build.version.oplusrom",
            "ro.rom.version",
            "ro.build.version.emui",
            "ro.build.version.magic",
            "ro.build.version.oneui"
        )
        private val GETPROP_LINE = Regex("^\\[([^]]+)]\\s*:\\s*\\[(.*)]$", RegexOption.MULTILINE)
    }
}
