package com.yubegreen.luonnotar.privileged

import org.json.JSONArray
import org.json.JSONObject

enum class BackgroundPolicyVendorFamily {
    XIAOMI,
    VIVO,
    OPPO,
    HUAWEI,
    SAMSUNG,
    AOSP,
    UNKNOWN
}

data class BackgroundPolicyDeviceIdentity(
    val family: BackgroundPolicyVendorFamily,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val product: String,
    val romName: String,
    val romVersion: String,
    val sdkInt: Int
) {
    fun displayName(): String = buildString {
        append(
            when (family) {
                BackgroundPolicyVendorFamily.XIAOMI -> "Xiaomi / Redmi / POCO"
                BackgroundPolicyVendorFamily.VIVO -> "vivo / iQOO"
                BackgroundPolicyVendorFamily.OPPO -> "OPPO / OnePlus / realme"
                BackgroundPolicyVendorFamily.HUAWEI -> "Huawei / Honor"
                BackgroundPolicyVendorFamily.SAMSUNG -> "Samsung"
                BackgroundPolicyVendorFamily.AOSP -> "AOSP / Google"
                BackgroundPolicyVendorFamily.UNKNOWN -> manufacturer.ifBlank { brand.ifBlank { "未知厂商" } }
            }
        )
        if (romName.isNotBlank()) {
            append(" · ")
            append(romName)
            if (romVersion.isNotBlank()) append(" ").append(romVersion)
        }
        if (model.isNotBlank()) append(" · ").append(model)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("family", family.name)
        .put("manufacturer", manufacturer)
        .put("brand", brand)
        .put("model", model)
        .put("product", product)
        .put("romName", romName)
        .put("romVersion", romVersion)
        .put("sdkInt", sdkInt)

    companion object {
        fun fromJson(json: JSONObject?): BackgroundPolicyDeviceIdentity {
            val value = json ?: JSONObject()
            return BackgroundPolicyDeviceIdentity(
                family = runCatching {
                    BackgroundPolicyVendorFamily.valueOf(value.optString("family"))
                }.getOrDefault(BackgroundPolicyVendorFamily.UNKNOWN),
                manufacturer = value.optString("manufacturer"),
                brand = value.optString("brand"),
                model = value.optString("model"),
                product = value.optString("product"),
                romName = value.optString("romName"),
                romVersion = value.optString("romVersion"),
                sdkInt = value.optInt("sdkInt", -1)
            )
        }
    }
}


enum class BackgroundPolicyCapabilityState {
    VERIFIED,
    APPLIED_UNVERIFIABLE,
    UNSUPPORTED,
    FAILED,
    DISABLED
}

data class BackgroundPolicyCapabilityResult(
    val name: String,
    val supported: Boolean,
    val applied: Boolean,
    val verified: Boolean,
    val detail: String
) {
    val state: BackgroundPolicyCapabilityState
        get() = when {
            detail == "disabled_by_config" -> BackgroundPolicyCapabilityState.DISABLED
            verified -> BackgroundPolicyCapabilityState.VERIFIED
            !supported -> BackgroundPolicyCapabilityState.UNSUPPORTED
            applied -> BackgroundPolicyCapabilityState.APPLIED_UNVERIFIABLE
            else -> BackgroundPolicyCapabilityState.FAILED
        }

    /**
     * Aggregate policy health is failed only by a capability that was supported
     * but could not be applied. Unsupported, deliberately disabled and
     * apply-only capabilities remain explicit in JSON without creating a false
     * whole-target failure.
     */
    val policySatisfied: Boolean
        get() = state != BackgroundPolicyCapabilityState.FAILED

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("supported", supported)
        .put("applied", applied)
        .put("verified", verified)
        .put("state", state.name)
        .put("policySatisfied", policySatisfied)
        .put("detail", detail.take(600))

    companion object {
        fun fromJson(json: JSONObject): BackgroundPolicyCapabilityResult =
            BackgroundPolicyCapabilityResult(
                name = json.optString("name"),
                supported = json.optBoolean("supported", false),
                applied = json.optBoolean("applied", false),
                verified = json.optBoolean("verified", false),
                detail = json.optString("detail")
            )
    }
}

data class BackgroundPolicyTargetResult(
    val packageName: String,
    val installed: Boolean,
    val fullyVerified: Boolean,
    val commandsAttempted: Int,
    val commandsSucceeded: Int,
    val capabilities: List<BackgroundPolicyCapabilityResult>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("packageName", packageName)
        .put("installed", installed)
        .put("fullyVerified", fullyVerified)
        .put("commandsAttempted", commandsAttempted)
        .put("commandsSucceeded", commandsSucceeded)
        .put("capabilities", JSONArray().apply { capabilities.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(json: JSONObject): BackgroundPolicyTargetResult {
            val capabilities = buildList {
                val array = json.optJSONArray("capabilities") ?: JSONArray()
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let {
                        add(BackgroundPolicyCapabilityResult.fromJson(it))
                    }
                }
            }
            return BackgroundPolicyTargetResult(
                packageName = json.optString("packageName"),
                installed = json.optBoolean("installed", false),
                fullyVerified = json.optBoolean("fullyVerified", false),
                commandsAttempted = json.optInt("commandsAttempted", 0),
                commandsSucceeded = json.optInt("commandsSucceeded", 0),
                capabilities = capabilities
            )
        }
    }
}

data class BackgroundPolicyReport(
    val source: String,
    val createdElapsed: Long,
    val device: BackgroundPolicyDeviceIdentity,
    val targets: List<BackgroundPolicyTargetResult>,
    val requiresOemUserAction: Boolean,
    val oemGuidance: String,
    val commandsAttempted: Int,
    val commandsSucceeded: Int
) {
    val installedTargets: Int get() = targets.count { it.installed }
    val verifiedTargets: Int get() = targets.count { it.installed && it.fullyVerified }
    val failedCommands: Int get() = (commandsAttempted - commandsSucceeded).coerceAtLeast(0)

    fun toJsonObject(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("source", source)
        .put("createdElapsed", createdElapsed)
        .put("device", device.toJson())
        .put("targets", JSONArray().apply { targets.forEach { put(it.toJson()) } })
        .put("requiresOemUserAction", requiresOemUserAction)
        .put("oemGuidance", oemGuidance)
        .put("commandsAttempted", commandsAttempted)
        .put("commandsSucceeded", commandsSucceeded)
        .put("installedTargets", installedTargets)
        .put("verifiedTargets", verifiedTargets)

    fun toJson(): String = toJsonObject().toString()

    fun conciseSummary(): String = buildString {
        append(device.displayName())
        append(" · ADB 白名单 ")
        append(verifiedTargets)
        append("/")
        append(installedTargets)
        if (failedCommands > 0) append(" · 命令失败 ").append(failedCommands)
        append(
            if (requiresOemUserAction) {
                " · 厂商私有层仍需确认"
            } else {
                " · 标准策略已验证"
            }
        )
    }

    companion object {
        const val SCHEMA = 2

        fun empty(): BackgroundPolicyReport = BackgroundPolicyReport(
            source = "never",
            createdElapsed = 0L,
            device = BackgroundPolicyDeviceIdentity(
                family = BackgroundPolicyVendorFamily.UNKNOWN,
                manufacturer = "",
                brand = "",
                model = "",
                product = "",
                romName = "",
                romVersion = "",
                sdkInt = -1
            ),
            targets = emptyList(),
            requiresOemUserAction = false,
            oemGuidance = "",
            commandsAttempted = 0,
            commandsSucceeded = 0
        )

        fun fromJson(raw: String?): BackgroundPolicyReport {
            if (raw.isNullOrBlank()) return empty()
            return runCatching {
                val json = JSONObject(raw)
                if (json.optInt("schema", -1) !in 1..SCHEMA) return@runCatching empty()
                val targets = buildList {
                    val array = json.optJSONArray("targets") ?: JSONArray()
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let {
                            add(BackgroundPolicyTargetResult.fromJson(it))
                        }
                    }
                }
                BackgroundPolicyReport(
                    source = json.optString("source", "unknown"),
                    createdElapsed = json.optLong("createdElapsed", 0L),
                    device = BackgroundPolicyDeviceIdentity.fromJson(json.optJSONObject("device")),
                    targets = targets,
                    requiresOemUserAction = json.optBoolean("requiresOemUserAction", false),
                    oemGuidance = json.optString("oemGuidance"),
                    commandsAttempted = json.optInt("commandsAttempted", 0),
                    commandsSucceeded = json.optInt("commandsSucceeded", 0)
                )
            }.getOrElse { empty() }
        }
    }
}

data class NumericOemAppOp(
    val code: Int,
    val label: String
)

object BackgroundPolicyVendorDetector {
    fun detect(properties: Map<String, String>): BackgroundPolicyDeviceIdentity {
        fun prop(name: String): String = properties[name].orEmpty().trim()
        val manufacturer = prop("ro.product.manufacturer")
        val brand = prop("ro.product.brand")
        val model = prop("ro.product.model")
        val product = prop("ro.product.name")
        val haystack = listOf(
            manufacturer,
            brand,
            product,
            prop("ro.product.vendor.brand"),
            prop("ro.product.system.brand")
        ).joinToString(" ").lowercase()

        val miui = prop("ro.miui.ui.version.name")
        val hyperOs = prop("ro.mi.os.version.name").ifBlank { prop("ro.mi.os.version.incremental") }
        val vivo = prop("ro.vivo.os.version").ifBlank { prop("ro.vivo.os.name") }
        val oppo = prop("ro.build.version.opporom")
            .ifBlank { prop("ro.build.version.oplusrom") }
            .ifBlank { prop("ro.rom.version") }
        val emui = prop("ro.build.version.emui")
        val magic = prop("ro.build.version.magic")
        val oneUi = prop("ro.build.version.oneui")

        val family = when {
            hyperOs.isNotBlank() || miui.isNotBlank() ||
                listOf("xiaomi", "redmi", "poco").any(haystack::contains) ->
                BackgroundPolicyVendorFamily.XIAOMI
            vivo.isNotBlank() || listOf("vivo", "iqoo").any(haystack::contains) ->
                BackgroundPolicyVendorFamily.VIVO
            oppo.isNotBlank() || listOf("oppo", "oneplus", "realme", "oplus").any(haystack::contains) ->
                BackgroundPolicyVendorFamily.OPPO
            emui.isNotBlank() || magic.isNotBlank() || listOf("huawei", "honor").any(haystack::contains) ->
                BackgroundPolicyVendorFamily.HUAWEI
            oneUi.isNotBlank() || haystack.contains("samsung") ->
                BackgroundPolicyVendorFamily.SAMSUNG
            listOf("google", "aosp").any(haystack::contains) ->
                BackgroundPolicyVendorFamily.AOSP
            manufacturer.isNotBlank() || brand.isNotBlank() ->
                BackgroundPolicyVendorFamily.UNKNOWN
            else -> BackgroundPolicyVendorFamily.AOSP
        }

        val romName: String
        val romVersion: String
        when (family) {
            BackgroundPolicyVendorFamily.XIAOMI -> {
                romName = if (hyperOs.isNotBlank()) "HyperOS" else "MIUI"
                romVersion = hyperOs.ifBlank { miui }
            }
            BackgroundPolicyVendorFamily.VIVO -> {
                romName = if (haystack.contains("iqoo")) "OriginOS / iQOO" else "OriginOS / Funtouch OS"
                romVersion = vivo
            }
            BackgroundPolicyVendorFamily.OPPO -> {
                romName = when {
                    haystack.contains("oneplus") -> "OxygenOS"
                    haystack.contains("realme") -> "realme UI"
                    else -> "ColorOS"
                }
                romVersion = oppo
            }
            BackgroundPolicyVendorFamily.HUAWEI -> {
                romName = if (magic.isNotBlank() || haystack.contains("honor")) "MagicOS" else "EMUI"
                romVersion = magic.ifBlank { emui }
            }
            BackgroundPolicyVendorFamily.SAMSUNG -> {
                romName = "One UI"
                romVersion = oneUi
            }
            BackgroundPolicyVendorFamily.AOSP -> {
                romName = "AOSP"
                romVersion = prop("ro.build.version.release")
            }
            BackgroundPolicyVendorFamily.UNKNOWN -> {
                romName = ""
                romVersion = ""
            }
        }

        return BackgroundPolicyDeviceIdentity(
            family = family,
            manufacturer = manufacturer,
            brand = brand,
            model = model,
            product = product,
            romName = romName,
            romVersion = romVersion,
            sdkInt = prop("ro.build.version.sdk").toIntOrNull() ?: -1
        )
    }

    fun guidance(family: BackgroundPolicyVendorFamily): String = when (family) {
        BackgroundPolicyVendorFamily.XIAOMI ->
            "已尝试写入 HyperOS/MIUI 的隐藏开机广播、后台自启动 AppOp；HyperOS 3 还会合并 cloud_lowlatency_whitelist。若私有能力未验证，仍需在系统设置中手动开启“后台自启动”和“省电策略：无限制”。"
        BackgroundPolicyVendorFamily.VIVO ->
            "ADB 标准层已自动处理；OriginOS/iQOO 的后台高耗电、允许后台运行和自启动属于厂商私有层，需要在 i 管家或应用设置中确认。"
        BackgroundPolicyVendorFamily.OPPO ->
            "ADB 标准层已自动处理；ColorOS/OxygenOS/realme UI 的自启动、关联启动和后台耗电管理属于厂商私有层，需要在系统设置中确认。"
        BackgroundPolicyVendorFamily.HUAWEI ->
            "ADB 标准层已自动处理；EMUI/MagicOS 的应用启动管理属于厂商私有层，需要关闭自动管理并允许后台活动。"
        BackgroundPolicyVendorFamily.SAMSUNG ->
            "ADB 标准层已自动处理；One UI 的深度休眠应用和后台使用限制仍需在电池设置中确认。"
        BackgroundPolicyVendorFamily.AOSP ->
            "设备未检测到额外厂商冻结层；以已验证的 Android 标准策略为准。"
        BackgroundPolicyVendorFamily.UNKNOWN ->
            "已应用并验证 Android 标准策略；未识别到可安全写入的厂商私有白名单接口。"
    }

    fun symbolicOemAppOps(family: BackgroundPolicyVendorFamily): List<String> = when (family) {
        BackgroundPolicyVendorFamily.XIAOMI -> listOf(
            "AUTO_START",
            "MIUI_AUTO_START",
            "START_IN_BACKGROUND"
        )
        BackgroundPolicyVendorFamily.VIVO -> listOf(
            "AUTO_START",
            "START_IN_BACKGROUND"
        )
        BackgroundPolicyVendorFamily.OPPO -> listOf(
            "AUTO_START",
            "START_IN_BACKGROUND"
        )
        BackgroundPolicyVendorFamily.HUAWEI -> listOf(
            "AUTO_START",
            "START_IN_BACKGROUND"
        )
        BackgroundPolicyVendorFamily.SAMSUNG -> listOf(
            "START_IN_BACKGROUND"
        )
        BackgroundPolicyVendorFamily.AOSP,
        BackgroundPolicyVendorFamily.UNKNOWN -> emptyList()
    }

    /**
     * MIUI/HyperOS keeps boot delivery and autostart behind vendor AppOps that are not exposed by
     * their symbolic names on every build. The numeric IDs are stable across the observed MIUI
     * framework family and are only attempted after the device has been identified as Xiaomi.
     */
    fun numericOemAppOps(family: BackgroundPolicyVendorFamily): List<NumericOemAppOp> = when (family) {
        BackgroundPolicyVendorFamily.XIAOMI -> listOf(
            NumericOemAppOp(code = 10007, label = "boot_completed"),
            NumericOemAppOp(code = 10008, label = "auto_start")
        )
        else -> emptyList()
    }

    fun supportsXiaomiCloudLowLatencyWhitelist(
        device: BackgroundPolicyDeviceIdentity
    ): Boolean =
        device.family == BackgroundPolicyVendorFamily.XIAOMI &&
            (
                device.sdkInt >= 36 ||
                    device.romVersion.startsWith("OS3", ignoreCase = true) ||
                    device.romVersion.startsWith("3.")
            )

    fun requiresPrivateLayerConfirmation(family: BackgroundPolicyVendorFamily): Boolean =
        family !in setOf(BackgroundPolicyVendorFamily.AOSP)
}

object BackgroundPolicyOutputParser {
    fun standbyBucketActive(raw: String): Boolean {
        val normalized = raw.trim().lowercase()
        return normalized == "10" || normalized == "active" ||
            Regex("(?:^|\\s)(?:10|active)(?:$|\\s)").containsMatchIn(normalized)
    }

    fun inactiveFalse(raw: String): Boolean =
        Regex("(?:^|[=:\\s])false(?:$|\\s)", RegexOption.IGNORE_CASE)
            .containsMatchIn(raw.trim())

    fun appOpAllowed(raw: String): Boolean =
        Regex("(?:^|[=:\\s])allow(?:$|[;\\s])", RegexOption.IGNORE_CASE)
            .containsMatchIn(raw)

    fun hibernationDisabled(raw: String): Boolean =
        Regex("(?:^|[=:\\s])false(?:$|\\s)", RegexOption.IGNORE_CASE)
            .containsMatchIn(raw.trim())

    fun packageStoppedFalse(raw: String): Boolean =
        Regex("""\bstopped=false\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(raw)

    fun deviceIdleContains(raw: String, packageName: String): Boolean =
        raw.lineSequence().any { line ->
            val trimmed = line.trim()
            trimmed == packageName ||
                trimmed.endsWith("=$packageName") ||
                Regex("(?:^|[^A-Za-z0-9_.])${Regex.escape(packageName)}(?:$|[^A-Za-z0-9_.])")
                    .containsMatchIn(trimmed)
        }

    fun delimitedSettingContains(raw: String, packageName: String): Boolean =
        parseDelimitedSetting(raw).contains(packageName)

    fun mergeDelimitedSetting(raw: String, packageName: String): String =
        (parseDelimitedSetting(raw) + packageName)
            .filter(GuardianEngineConfig::isSafePackageName)
            .distinct()
            .joinToString(",")

    private fun parseDelimitedSetting(raw: String): List<String> {
        val normalized = raw.trim()
        if (
            normalized.isBlank() ||
            normalized.equals("null", ignoreCase = true) ||
            normalized.equals("none", ignoreCase = true)
        ) {
            return emptyList()
        }
        return normalized
            .split(',', ';', '\n', '\r', '\t', ' ')
            .map(String::trim)
            .filter(String::isNotBlank)
    }

    fun netPolicyContainsUid(raw: String, uid: Int): Boolean =
        raw.lineSequence().any { line ->
            Regex("(?:^|\\D)${Regex.escape(uid.toString())}(?:$|\\D)")
                .containsMatchIn(line)
        }
}
