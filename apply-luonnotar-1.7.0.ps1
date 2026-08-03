param(
    [string]$ProjectRoot = (Get-Location).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot = [System.IO.Path]::GetFullPath($ProjectRoot)
$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Read-NormalizedText {
    param([Parameter(Mandatory = $true)][string]$Path)

    return [System.IO.File]::ReadAllText($Path).
        Replace("`r`n", "`n").
        Replace("`r", "`n")
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )

    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }

    [System.IO.File]::WriteAllText($Path, $Text, $Utf8NoBom)
}

function Replace-ExactlyOnce {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $count = [regex]::Matches(
        $Text,
        [regex]::Escape($Old)
    ).Count

    if ($count -ne 1) {
        throw "$Description：预期匹配 1 次，实际匹配 $count 次。停止修改。"
    }

    return $Text.Replace($Old, $New)
}

function Replace-ExpectedCount {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Old,
        [Parameter(Mandatory = $true)][string]$New,
        [Parameter(Mandatory = $true)][int]$ExpectedCount,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $count = [regex]::Matches(
        $Text,
        [regex]::Escape($Old)
    ).Count

    if ($count -ne $ExpectedCount) {
        throw "$Description：预期匹配 $ExpectedCount 次，实际匹配 $count 次。停止修改。"
    }

    return $Text.Replace($Old, $New)
}

$required = @(
    "gradlew.bat",
    "app\build.gradle.kts",
    "app\src\main\java\com\yubegreen\luonnotar\MainActivity.kt",
    "app\src\main\java\com\yubegreen\luonnotar\service\FcmGuardianService.kt",
    "app\src\main\java\com\yubegreen\luonnotar\service\GuardianPowerPolicy.kt",
    "app\src\main\java\com\yubegreen\luonnotar\service\GuardianProfilePolicy.kt",
    "app\src\main\java\com\yubegreen\luonnotar\ui\visual\GuardianExperimentDialog.kt",
    "app\src\main\java\com\yubegreen\luonnotar\util\LuonnotarPreferences.kt",
    "app\src\test\java\com\yubegreen\luonnotar\service\GuardianPowerPolicyTest.kt",
    "app\src\test\java\com\yubegreen\luonnotar\service\GuardianProfilePolicyTest.kt",
    "app\src\main\java\com\yubegreen\luonnotar\policy\PolicyManager.kt",
    "app\src\main\res\raw\luonnotar_policy_zh.txt"
)

foreach ($relative in $required) {
    $full = Join-Path $ProjectRoot $relative

    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
        throw "缺少必需文件：$full"
    }
}

$buildPath = Join-Path $ProjectRoot "app\build.gradle.kts"
$buildBefore = Read-NormalizedText $buildPath

if (
    $buildBefore -notmatch 'versionCode\s*=\s*26' -or
    $buildBefore -notmatch 'versionName\s*=\s*"1\.6\.9"'
) {
    throw "当前工程不是预期的 1.6.9 / versionCode 26。不要强行套补丁。"
}

$prefsPath = Join-Path $ProjectRoot `
    "app\src\main\java\com\yubegreen\luonnotar\util\LuonnotarPreferences.kt"

if (
    (Read-NormalizedText $prefsPath) -match
    'KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD'
) {
    throw "工程已经包含熄屏 CPU Guard 配置；未重复应用补丁。"
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupRoot = Join-Path $ProjectRoot `
    "manual-backup-1.7.0-cpu-guard-ui-$timestamp"

$backupTargets = @(
    "app\build.gradle.kts",
    "app\src\main\java\com\yubegreen\luonnotar\MainActivity.kt",
    "app\src\main\java\com\yubegreen\luonnotar\service\FcmGuardianService.kt",
    "app\src\main\java\com\yubegreen\luonnotar\service\GuardianPowerPolicy.kt",
    "app\src\main\java\com\yubegreen\luonnotar\service\GuardianProfilePolicy.kt",
    "app\src\main\java\com\yubegreen\luonnotar\ui\visual\GuardianExperimentDialog.kt",
    "app\src\main\java\com\yubegreen\luonnotar\util\LuonnotarPreferences.kt",
    "app\src\test\java\com\yubegreen\luonnotar\service\GuardianPowerPolicyTest.kt",
    "app\src\test\java\com\yubegreen\luonnotar\service\GuardianProfilePolicyTest.kt",
    "app\src\main\java\com\yubegreen\luonnotar\policy\PolicyManager.kt",
    "app\src\main\res\raw\luonnotar_policy_zh.txt",
    "tools\test-iqoo-aggressive.ps1"
)

foreach ($relative in $backupTargets) {
    $source = Join-Path $ProjectRoot $relative

    if (Test-Path -LiteralPath $source -PathType Leaf) {
        $destination = Join-Path $backupRoot $relative

        New-Item `
            -ItemType Directory `
            -Path (Split-Path -Parent $destination) `
            -Force |
            Out-Null

        Copy-Item `
            -LiteralPath $source `
            -Destination $destination `
            -Force
    }
}

try {
    # 1. 版本号
    $build = $buildBefore
    $build = Replace-ExactlyOnce `
        $build `
        'versionCode = 26' `
        'versionCode = 27' `
        "升级 versionCode"

    $build = Replace-ExactlyOnce `
        $build `
        'versionName = "1.6.9"' `
        'versionName = "1.7.0"' `
        "升级 versionName"

    Write-Utf8NoBom $buildPath $build

    # 2. SharedPreferences 配置键与真实连续锁状态
    $prefs = Read-NormalizedText $prefsPath

    $prefs = Replace-ExactlyOnce $prefs @'
    const val KEY_EXPERIMENT_PERMANENT_CPU_LOCK = "permanent_cpu_lock"
    const val KEY_EXPERIMENT_SCOPED_CPU_LOCK = "scoped_cpu_lock"
'@ @'
    const val KEY_EXPERIMENT_PERMANENT_CPU_LOCK = "permanent_cpu_lock"
    const val KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD =
        "screen_off_cpu_guard"
    const val KEY_EXPERIMENT_SCOPED_CPU_LOCK = "scoped_cpu_lock"
'@ "增加熄屏 CPU Guard 配置键"

    $prefs = Replace-ExactlyOnce $prefs @'
    const val KEY_WAKE_LOCK = "wake_lock_held"
    const val KEY_WIFI_LOCK = "wifi_lock_held"
'@ @'
    const val KEY_WAKE_LOCK = "wake_lock_held"
    const val KEY_CONTINUOUS_WAKE_LOCK =
        "continuous_wake_lock_held"
    const val KEY_WIFI_LOCK = "wifi_lock_held"
'@ "增加连续 CPU Lock 实际状态键"

    $prefs = Replace-ExpectedCount $prefs @'
            .putBoolean(KEY_WAKE_LOCK, false)
            .putBoolean(KEY_WIFI_LOCK, false)
'@ @'
            .putBoolean(KEY_WAKE_LOCK, false)
            .putBoolean(KEY_CONTINUOUS_WAKE_LOCK, false)
            .putBoolean(KEY_WIFI_LOCK, false)
'@ 2 "开机及 Keeper 重建时清除连续锁状态"

    Write-Utf8NoBom $prefsPath $prefs

    # 3. 完整重写运行配置策略
    $profilePath = Join-Path $ProjectRoot `
        "app\src\main\java\com\yubegreen\luonnotar\service\GuardianProfilePolicy.kt"

    $profileSource = @'
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
        LuonnotarPreferences.KEY_EXPERIMENT_FREQUENT_NOTIFICATION_REFRESH,
        LuonnotarPreferences.KEY_MONITOR_GMS,
        LuonnotarPreferences.KEY_MONITOR_WHATSAPP,
        LuonnotarPreferences.KEY_MONITOR_WHATSAPP_BUSINESS
    )

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
                    frequentNotificationRefresh = false
                )

            GuardianRuntimeProfile.STANDARD ->
                GuardianExperimentSettings(
                    permanentCpuLock = false,
                    screenOffCpuGuard = false,
                    scopedCpuLock = true,
                    highPerfWifiLock = false,
                    screenEventProbe = false,
                    periodicDns = true,
                    periodicHttps = true,
                    automaticMtalk = false,
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
                    frequentNotificationRefresh = false
                )

            GuardianRuntimeProfile.LAB_EXTREME ->
                labLevel(0)
        }

    fun labLevel(level: Int): GuardianExperimentSettings {
        val safeLevel = level.coerceIn(0, 4)

        return GuardianExperimentSettings(
            permanentCpuLock = safeLevel >= 4,
            screenOffCpuGuard = false,
            scopedCpuLock = safeLevel in 1..3,
            highPerfWifiLock = safeLevel >= 4,
            screenEventProbe = safeLevel >= 2,
            periodicDns = safeLevel >= 3,
            periodicHttps = safeLevel >= 3,
            automaticMtalk = false,
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
            LuonnotarPreferences.KEY_EXPERIMENT_FREQUENT_NOTIFICATION_REFRESH to
                defaults.frequentNotificationRefresh
        )

        values.forEach { (key, value) ->
            if (!prefs.contains(key)) {
                editor.putBoolean(key, value)
                changed = true
            }
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
                    prefs.getBoolean(
                        LuonnotarPreferences
                            .KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD,
                        defaults.screenOffCpuGuard
                    ),
                scopedCpuLock =
                    prefs.getBoolean(
                        LuonnotarPreferences
                            .KEY_EXPERIMENT_SCOPED_CPU_LOCK,
                        defaults.scopedCpuLock
                    ),
                highPerfWifiLock =
                    labOnly && prefs.getBoolean(
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
'@

    Write-Utf8NoBom $profilePath $profileSource

    # 4. 纯 CPU 策略
    $powerPath = Join-Path $ProjectRoot `
        "app\src\main\java\com\yubegreen\luonnotar\service\GuardianPowerPolicy.kt"

    $powerSource = @'
package com.yubegreen.luonnotar.service

data class GuardianPowerInput(
    val guardianActive: Boolean,
    val currentService: Boolean,
    val profile: GuardianRuntimeProfile,
    val screenInteractive: Boolean,
    val screenOffCpuGuard: Boolean,
    val labPermanentCpuLock: Boolean
)

data class GuardianPowerDecision(
    val holdCpuLock: Boolean,
    val scope: String
)

object GuardianPowerPolicy {
    const val IQOO_TICK_SECONDS = 30L

    fun decide(
        input: GuardianPowerInput
    ): GuardianPowerDecision {
        if (!input.guardianActive) {
            return GuardianPowerDecision(
                holdCpuLock = false,
                scope = "guardian_inactive"
            )
        }

        if (!input.currentService) {
            return GuardianPowerDecision(
                holdCpuLock = false,
                scope = "stale_service"
            )
        }

        if (input.profile == GuardianRuntimeProfile.ADB_PASSIVE) {
            return GuardianPowerDecision(
                holdCpuLock = false,
                scope = "adb_passive_no_continuous_lock"
            )
        }

        if (
            input.profile == GuardianRuntimeProfile.LAB_EXTREME &&
            input.labPermanentCpuLock
        ) {
            return GuardianPowerDecision(
                holdCpuLock = true,
                scope = "lab_permanent"
            )
        }

        if (input.screenOffCpuGuard) {
            return GuardianPowerDecision(
                holdCpuLock = !input.screenInteractive,
                scope = if (input.screenInteractive) {
                    "screen_off_cpu_guard_screen_on"
                } else {
                    "screen_off_cpu_guard_active"
                }
            )
        }

        return GuardianPowerDecision(
            holdCpuLock = false,
            scope = "screen_off_cpu_guard_disabled"
        )
    }
}
'@

    Write-Utf8NoBom $powerPath $powerSource

    # 5. 服务接入独立 Guard 与真实连续锁状态
    $fcmPath = Join-Path $ProjectRoot `
        "app\src\main\java\com\yubegreen\luonnotar\service\FcmGuardianService.kt"

    $fcm = Read-NormalizedText $fcmPath

    $fcm = Replace-ExactlyOnce $fcm @'
                profile = settings.profile,
                screenInteractive = screenInteractive,
                labPermanentCpuLock =
                    settings.experiments.permanentCpuLock
'@ @'
                profile = settings.profile,
                screenInteractive = screenInteractive,
                screenOffCpuGuard =
                    settings.experiments.screenOffCpuGuard,
                labPermanentCpuLock =
                    settings.experiments.permanentCpuLock
'@ "向 CPU 策略传递独立 Guard 配置"

    $fcm = Replace-ExactlyOnce $fcm @'
            LuonnotarPreferences.deviceProtected(this).edit()
                .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, true).apply()
'@ @'
            LuonnotarPreferences.deviceProtected(this).edit()
                .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, true)
                .putBoolean(
                    LuonnotarPreferences.KEY_CONTINUOUS_WAKE_LOCK,
                    true
                )
                .apply()
'@ "记录连续锁获取状态"

    $fcm = Replace-ExactlyOnce $fcm @'
            val anyCpuLockHeld =
                ::scopedWakeLock.isInitialized && scopedWakeLock.isHeld
            LuonnotarPreferences.deviceProtected(this).edit()
                .putBoolean(
                    LuonnotarPreferences.KEY_WAKE_LOCK,
                    anyCpuLockHeld
                )
                .apply()
'@ @'
            val anyCpuLockHeld =
                ::scopedWakeLock.isInitialized && scopedWakeLock.isHeld
            LuonnotarPreferences.deviceProtected(this).edit()
                .putBoolean(
                    LuonnotarPreferences.KEY_WAKE_LOCK,
                    anyCpuLockHeld
                )
                .putBoolean(
                    LuonnotarPreferences.KEY_CONTINUOUS_WAKE_LOCK,
                    false
                )
                .apply()
'@ "记录连续锁释放状态"

    $fcm = Replace-ExpectedCount $fcm @'
                    .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, false)
                    .putBoolean(LuonnotarPreferences.KEY_WIFI_LOCK, false)
'@ @'
                    .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, false)
                    .putBoolean(
                        LuonnotarPreferences.KEY_CONTINUOUS_WAKE_LOCK,
                        false
                    )
                    .putBoolean(LuonnotarPreferences.KEY_WIFI_LOCK, false)
'@ 1 "停止命令清除连续锁状态"

    $fcm = Replace-ExpectedCount $fcm @'
            .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, false)
            .putBoolean(LuonnotarPreferences.KEY_WIFI_LOCK, false)
'@ @'
            .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, false)
            .putBoolean(
                LuonnotarPreferences.KEY_CONTINUOUS_WAKE_LOCK,
                false
            )
            .putBoolean(LuonnotarPreferences.KEY_WIFI_LOCK, false)
'@ 2 "销毁及统一释放时清除连续锁状态"

    $fcm = Replace-ExactlyOnce $fcm @'
                if (settings.cooperative) {
                    "iqoo_cpu_guard_acquired"
                } else {
                    "wake_lock_acquired"
                },
'@ @'
                if (
                    decision.scope.startsWith(
                        "screen_off_cpu_guard"
                    )
                ) {
                    "screen_off_cpu_guard_acquired"
                } else {
                    "wake_lock_acquired"
                },
'@ "使用跨厂商 CPU Guard 获取事件"

    $fcm = Replace-ExactlyOnce $fcm @'
                if (settings.cooperative) {
                    "iqoo_cpu_guard_released"
                } else {
                    "wake_lock_released"
                },
'@ @'
                if (
                    decision.scope.startsWith(
                        "screen_off_cpu_guard"
                    )
                ) {
                    "screen_off_cpu_guard_released"
                } else {
                    "wake_lock_released"
                },
'@ "使用跨厂商 CPU Guard 释放事件"

    Write-Utf8NoBom $fcmPath $fcm

    # 6. A/B 设置弹窗加入独立开关与 STANDARD 模式
    $dialogPath = Join-Path $ProjectRoot `
        "app\src\main\java\com\yubegreen\luonnotar\ui\visual\GuardianExperimentDialog.kt"

    $dialog = Read-NormalizedText $dialogPath

    $dialog = Replace-ExactlyOnce $dialog @'
    private val labOnlyKeys = setOf(
        LuonnotarPreferences.KEY_EXPERIMENT_PERMANENT_CPU_LOCK,
        LuonnotarPreferences.KEY_EXPERIMENT_HIGH_PERF_WIFI_LOCK,
        LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_EVENT_PROBE
    )

    private val definitions = listOf(
        LuonnotarPreferences.KEY_EXPERIMENT_SCOPED_CPU_LOCK to
            "任务级 CPU Lock（10 秒）",
'@ @'
    private val labOnlyKeys = setOf(
        LuonnotarPreferences.KEY_EXPERIMENT_PERMANENT_CPU_LOCK,
        LuonnotarPreferences.KEY_EXPERIMENT_HIGH_PERF_WIFI_LOCK,
        LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_EVENT_PROBE
    )
    private val passiveDisabledKeys = setOf(
        LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD
    )

    private val definitions = listOf(
        LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD to
            "熄屏 CPU Guard（亮屏释放）",
        LuonnotarPreferences.KEY_EXPERIMENT_SCOPED_CPU_LOCK to
            "任务级 CPU Lock（10 秒）",
'@ "弹窗加入独立 CPU Guard"

    $dialog = Replace-ExactlyOnce $dialog @'
                "iQOO 默认使用协作模式：先静默、只监听，异常时短暂出手。" +
                    "ADB 一次性隐身验证运行 60 秒后自动关闭守护。" +
                    "实验室模式不会自动开启高强度项目。"
'@ @'
                "熄屏 CPU Guard 可由任何厂商设备手动启用，" +
                    "只控制连续 CPU WakeLock，不会联动高性能 Wi-Fi。" +
                    "ADB 一次性验证固定禁用连续锁。"
'@ "更新策略说明"

    $dialog = Replace-ExactlyOnce $dialog @'
        listOf(
            GuardianRuntimeProfile.IQOO_COOPERATIVE to "自适应",
            GuardianRuntimeProfile.ADB_PASSIVE to "ADB 一次性验证",
            GuardianRuntimeProfile.LAB_EXTREME to "实验室"
        ).forEachIndexed { index, (profile, label) ->
'@ @'
        listOf(
            GuardianRuntimeProfile.STANDARD to "标准",
            GuardianRuntimeProfile.IQOO_COOPERATIVE to "自适应",
            GuardianRuntimeProfile.ADB_PASSIVE to "ADB",
            GuardianRuntimeProfile.LAB_EXTREME to "实验室"
        ).forEachIndexed { index, (profile, label) ->
'@ "显示 STANDARD 模式"

    $dialog = Replace-ExactlyOnce `
        $dialog `
        'profiles.addView(option, weighted(index, 3))' `
        'profiles.addView(option, weighted(index, 4))' `
        "调整四个模式按钮布局"

    $dialog = Replace-ExactlyOnce $dialog @'
    private fun applySettings(settings: GuardianExperimentSettings) {
        values[LuonnotarPreferences.KEY_EXPERIMENT_PERMANENT_CPU_LOCK] =
            settings.permanentCpuLock
        values[LuonnotarPreferences.KEY_EXPERIMENT_SCOPED_CPU_LOCK] =
'@ @'
    private fun applySettings(settings: GuardianExperimentSettings) {
        values[LuonnotarPreferences.KEY_EXPERIMENT_PERMANENT_CPU_LOCK] =
            settings.permanentCpuLock
        values[
            LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD
        ] = settings.screenOffCpuGuard
        values[LuonnotarPreferences.KEY_EXPERIMENT_SCOPED_CPU_LOCK] =
'@ "应用预设时同步 CPU Guard"

    $dialog = Replace-ExactlyOnce $dialog @'
                button.isEnabled =
                    selectedProfile == GuardianRuntimeProfile.LAB_EXTREME ||
                        key !in labOnlyKeys
'@ @'
                button.isEnabled =
                    (
                        selectedProfile ==
                            GuardianRuntimeProfile.LAB_EXTREME ||
                            key !in labOnlyKeys
                        ) &&
                        (
                            selectedProfile !=
                                GuardianRuntimeProfile.ADB_PASSIVE ||
                                key !in passiveDisabledKeys
                            )
'@ "ADB 一次性验证禁用连续 Guard"

    Write-Utf8NoBom $dialogPath $dialog

    # 7. 主界面直接按钮、状态与实际锁证据
    $mainPath = Join-Path $ProjectRoot `
        "app\src\main\java\com\yubegreen\luonnotar\MainActivity.kt"

    $main = Read-NormalizedText $mainPath

    $main = Replace-ExactlyOnce $main @'
    private lateinit var aggressiveModeButton: Button
    private lateinit var serviceActionHint: TextView
'@ @'
    private lateinit var aggressiveModeButton: Button
    private lateinit var cpuGuardSummary: TextView
    private lateinit var cpuGuardButton: Button
    private lateinit var serviceActionHint: TextView
'@ "增加主界面 CPU Guard 控件字段"

    $main = Replace-ExactlyOnce $main @'
        root.addView(aggressiveModeButton)

        root.addView(sectionTitle("外观"))
'@ @'
        root.addView(aggressiveModeButton)

        root.addView(sectionTitle("可靠性控制"))
        cpuGuardSummary = TextView(this).apply {
            textSize = if (tabletLayout) 16f else 13f
            setTextColor(palette.secondary)
            setPadding(dp(4), 0, dp(4), dp(8))
            text = "正在读取熄屏 CPU Guard 状态…"
        }
        root.addView(cpuGuardSummary)
        cpuGuardButton = actionButton("开启熄屏 CPU Guard") {
            toggleScreenOffCpuGuard()
        }
        root.addView(cpuGuardButton)
        root.addView(TextView(this).apply {
            text =
                "只在守护运行且屏幕关闭时保持 CPU 醒着；" +
                    "亮屏立即释放，不会自动开启高性能 Wi-Fi Lock。"
            textSize = if (tabletLayout) 15f else 12f
            setTextColor(palette.secondary)
            setPadding(dp(4), dp(2), dp(4), dp(12))
        })

        root.addView(sectionTitle("外观"))
'@ "主界面加入独立 CPU Guard 控件"

    $main = Replace-ExactlyOnce $main @'
        val wakeLockHeld = serviceAlive && status.boolean(LuonnotarPreferences.KEY_WAKE_LOCK)
        val wifiLockHeld = serviceAlive && status.boolean(LuonnotarPreferences.KEY_WIFI_LOCK)
        val underlyingTransport = status.string(LuonnotarPreferences.KEY_TRANSPORT, "UNKNOWN")
'@ @'
        val wakeLockHeld =
            serviceAlive &&
                status.boolean(LuonnotarPreferences.KEY_WAKE_LOCK)
        val continuousWakeLockHeld =
            serviceAlive &&
                status.boolean(
                    LuonnotarPreferences.KEY_CONTINUOUS_WAKE_LOCK
                )
        val wifiLockHeld =
            serviceAlive &&
                status.boolean(LuonnotarPreferences.KEY_WIFI_LOCK)
        val screenOffCpuGuard =
            status.boolean(
                LuonnotarPreferences
                    .KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD
            )
        val screenInteractive =
            getSystemService(PowerManager::class.java).isInteractive
        val underlyingTransport = status.string(LuonnotarPreferences.KEY_TRANSPORT, "UNKNOWN")
'@ "读取独立 Guard 与连续锁状态"

    $main = Replace-ExactlyOnce $main @'
        val enabledExperiments = listOfNotNull(
            "短时 CPU".takeIf { scopedCpuLock },
'@ @'
        val enabledExperiments = listOfNotNull(
            "熄屏 CPU Guard".takeIf { screenOffCpuGuard },
            "短时 CPU".takeIf { scopedCpuLock },
'@ "A/B 列表显示 CPU Guard"

    $main = Replace-ExactlyOnce $main @'
                    scopedCpuLock &&
                        !permanentCpuLock &&
'@ @'
                    screenOffCpuGuard &&
                        scopedCpuLock &&
                        !permanentCpuLock &&
'@ "iQOO 默认策略校验 Guard 已开启"

    $main = Replace-ExactlyOnce $main @'
        val wifiRequired = underlyingTransport == "WIFI"
'@ @'
        addStatus(
            "熄屏 CPU Guard",
            when {
                !screenOffCpuGuard ->
                    "关闭 · 不持有连续 CPU WakeLock"
                !enabled || paused ->
                    "已配置 · 守护当前未运行"
                screenInteractive ->
                    "开启 · 当前亮屏 · 连续锁 ${
                        if (continuousWakeLockHeld) {
                            "异常仍持有"
                        } else {
                            "已释放"
                        }
                    }"
                continuousWakeLockHeld ->
                    "开启 · 当前熄屏 · 连续锁已持有"
                else ->
                    "开启 · 当前熄屏 · 连续锁尚未持有"
            },
            when {
                !screenOffCpuGuard ->
                    !continuousWakeLockHeld
                !enabled || paused || !serviceAlive ->
                    !continuousWakeLockHeld
                screenInteractive ->
                    !continuousWakeLockHeld
                else ->
                    continuousWakeLockHeld
            }
        )
        updateCpuGuardUi(
            status = status,
            serviceAlive = serviceAlive,
            screenInteractive = screenInteractive,
            continuousWakeLockHeld = continuousWakeLockHeld
        )

        val wifiRequired = underlyingTransport == "WIFI"
'@ "状态面板显示 Guard 配置与实际状态"

    $main = Replace-ExactlyOnce $main @'
                permanentCpuLock ->
                    wakeLockHeld &&
                        (!highPerfWifiLock || !wifiRequired || wifiLockHeld)
                highPerfWifiLock ->
                    !wakeLockHeld && (!wifiRequired || wifiLockHeld)
                else ->
                    !wifiLockHeld &&
                        (!wakeLockHeld || scopedCpuLock)
'@ @'
                permanentCpuLock ->
                    wakeLockHeld &&
                        (!highPerfWifiLock || !wifiRequired || wifiLockHeld)
                screenOffCpuGuard && !screenInteractive ->
                    continuousWakeLockHeld &&
                        (!highPerfWifiLock || !wifiRequired || wifiLockHeld)
                screenOffCpuGuard ->
                    !continuousWakeLockHeld &&
                        (!highPerfWifiLock || !wifiRequired || wifiLockHeld)
                highPerfWifiLock ->
                    !wakeLockHeld && (!wifiRequired || wifiLockHeld)
                else ->
                    !wifiLockHeld &&
                        (!wakeLockHeld || scopedCpuLock)
'@ "CPU/Wi-Fi 状态按独立 Guard 验收"

    $main = Replace-ExactlyOnce $main @'
        aggressiveModeButton.text =
            when (runtimeProfile) {
                GuardianRuntimeProfile.LAB_EXTREME ->
                    "调整实验室策略 · L$labLevel"
                GuardianRuntimeProfile.ADB_PASSIVE ->
                    "调整 ADB 隐身策略"
                else -> "调整自适应守护策略"
            }
'@ @'
        aggressiveModeButton.text =
            when (runtimeProfile) {
                GuardianRuntimeProfile.LAB_EXTREME ->
                    "调整实验室策略 · L$labLevel"
                GuardianRuntimeProfile.ADB_PASSIVE ->
                    "调整 ADB 隐身策略"
                GuardianRuntimeProfile.IQOO_COOPERATIVE ->
                    "调整 iQOO 自适应策略"
                GuardianRuntimeProfile.STANDARD ->
                    "调整标准守护策略"
            }
'@ "准确显示当前运行策略"

    $main = Replace-ExactlyOnce $main @'
                    GuardianRuntimeProfile.ADB_PASSIVE ->
                        "已应用 ADB 一次性隐身验证：运行 60 秒后自动关闭守护"
                    else ->
                        "已应用实验室 L$level；请观察 GMS 冻结次数、耗电和真实推送后再升级"
'@ @'
                    GuardianRuntimeProfile.ADB_PASSIVE ->
                        "已应用 ADB 一次性隐身验证：运行 60 秒后自动关闭守护"
                    GuardianRuntimeProfile.STANDARD ->
                        "已应用标准模式；熄屏 CPU Guard 可在主界面单独开启"
                    GuardianRuntimeProfile.LAB_EXTREME ->
                        "已应用实验室 L$level；请观察 GMS 冻结次数、耗电和真实推送后再升级"
'@ "修复 STANDARD 模式提示"

    $main = Replace-ExactlyOnce $main @'
    private fun toggleGmsBinderAnchor() {
'@ @'
    private fun toggleScreenOffCpuGuard() {
        val status = readGuardianStatus()

        if (
            !status.containsKey(
                LuonnotarPreferences.KEY_KEEPER_PROCESS_PID
            )
        ) {
            showGlassNotice(
                "Keeper 状态不可读，无法修改 CPU Guard",
                long = true
            )
            return
        }

        if (
            guardianProfile(status) ==
            GuardianRuntimeProfile.ADB_PASSIVE
        ) {
            showGlassNotice(
                "ADB 一次性验证必须保持无连续锁基线"
            )
            return
        }

        val enabled = status.boolean(
            LuonnotarPreferences
                .KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD
        )
        val newValue = !enabled

        if (
            !GuardianStatusClient.setExperiment(
                this,
                LuonnotarPreferences
                    .KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD,
                newValue
            )
        ) {
            showGlassNotice(
                "熄屏 CPU Guard 写入失败",
                long = true
            )
            return
        }

        LogManager.event(
            this,
            "screen_off_cpu_guard_config_changed",
            mapOf("enabled" to newValue)
        )

        if (
            status.boolean(LuonnotarPreferences.KEY_ENABLED) &&
            !status.boolean(LuonnotarPreferences.KEY_PAUSED)
        ) {
            runCatching {
                startService(
                    Intent(this, FcmGuardianService::class.java)
                        .setAction(
                            FcmGuardianService.ACTION_PROFILE_CHANGED
                        )
                )
            }.onFailure {
                showGlassNotice(
                    "配置已保存，但守护即时重算失败：${
                        it.javaClass.simpleName
                    }",
                    long = true
                )
            }
        }

        showGlassNotice(
            if (newValue) {
                "熄屏 CPU Guard 已开启；亮屏时不会持有连续锁"
            } else {
                "熄屏 CPU Guard 已关闭"
            }
        )
        renderStatus()
    }

    private fun updateCpuGuardUi(
        status: Bundle,
        serviceAlive: Boolean,
        screenInteractive: Boolean,
        continuousWakeLockHeld: Boolean
    ) {
        if (
            !::cpuGuardSummary.isInitialized ||
            !::cpuGuardButton.isInitialized
        ) return

        val profile = guardianProfile(status)
        val configured = status.boolean(
            LuonnotarPreferences
                .KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD
        )
        val passive =
            profile == GuardianRuntimeProfile.ADB_PASSIVE
        val statusAvailable = status.containsKey(
            LuonnotarPreferences.KEY_KEEPER_PROCESS_PID
        )

        cpuGuardButton.isEnabled =
            statusAvailable && !passive
        cpuGuardButton.text =
            if (configured) {
                "关闭熄屏 CPU Guard"
            } else {
                "开启熄屏 CPU Guard"
            }

        cpuGuardSummary.text = when {
            passive ->
                "ADB 一次性验证 · 固定关闭连续 CPU Lock"
            !configured ->
                "关闭 · 不影响任务级 10 秒 CPU Lock"
            !serviceAlive ->
                "已配置 · 守护未运行，当前未持有连续锁"
            screenInteractive && continuousWakeLockHeld ->
                "已配置 · 当前亮屏，但连续锁仍持有，等待策略释放"
            screenInteractive ->
                "已配置 · 当前亮屏，连续锁已释放"
            continuousWakeLockHeld ->
                "已配置 · 当前熄屏，连续锁已持有"
            else ->
                "已配置 · 当前熄屏，但连续锁尚未持有"
        }

        cpuGuardSummary.setTextColor(
            when {
                passive || !configured ->
                    palette().secondary
                !serviceAlive ->
                    palette().warning
                screenInteractive &&
                    !continuousWakeLockHeld ->
                    palette().good
                !screenInteractive &&
                    continuousWakeLockHeld ->
                    palette().good
                else ->
                    palette().warning
            }
        )
    }

    private fun toggleGmsBinderAnchor() {
'@ "增加主界面独立开关逻辑"

    $main = Replace-ExactlyOnce $main @'
            vendor.contains("xiaomi", true) || vendor.contains("redmi", true) ->
                "检测到小米/红米：\n• 努昂诺塔、当前 VPN（Proton/Tailscale）、WhatsApp、GMS 分别开启自启动\n• 电池策略设为“无限制”，允许后台数据，最近任务锁定\n• HyperOS 更新后重新检查\n• millet_white 仅作高级实验，不由本应用修改"
'@ @'
            vendor.contains("xiaomi", true) || vendor.contains("redmi", true) ->
                "检测到小米/红米：\n• 可在主界面手动开启“熄屏 CPU Guard”做单变量 A/B\n• 努昂诺塔、当前 VPN（Proton/Tailscale）、WhatsApp、GMS 分别开启自启动\n• 电池策略设为“无限制”，允许后台数据，最近任务锁定\n• HyperOS 更新后重新检查\n• millet_white 仅作高级实验，不由本应用修改"
'@ "红米向导说明独立 Guard"

    Write-Utf8NoBom $mainPath $main

    # 8. 单元测试
    $powerTestPath = Join-Path $ProjectRoot `
        "app\src\test\java\com\yubegreen\luonnotar\service\GuardianPowerPolicyTest.kt"

    $powerTest = @'
package com.yubegreen.luonnotar.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianPowerPolicyTest {
    private val base = GuardianPowerInput(
        guardianActive = true,
        currentService = true,
        profile = GuardianRuntimeProfile.STANDARD,
        screenInteractive = false,
        screenOffCpuGuard = true,
        labPermanentCpuLock = false
    )

    @Test
    fun `screen off guard works in standard profile`() {
        assertTrue(
            GuardianPowerPolicy.decide(base).holdCpuLock
        )
        assertFalse(
            GuardianPowerPolicy.decide(
                base.copy(screenInteractive = true)
            ).holdCpuLock
        )
    }

    @Test
    fun `disabled guard does not hold continuous lock`() {
        assertFalse(
            GuardianPowerPolicy.decide(
                base.copy(screenOffCpuGuard = false)
            ).holdCpuLock
        )
    }

    @Test
    fun `iqoo default guard follows screen state`() {
        val iqoo = base.copy(
            profile = GuardianRuntimeProfile.IQOO_COOPERATIVE
        )

        assertTrue(
            GuardianPowerPolicy.decide(iqoo).holdCpuLock
        )
        assertFalse(
            GuardianPowerPolicy.decide(
                iqoo.copy(screenInteractive = true)
            ).holdCpuLock
        )
    }

    @Test
    fun `adb passive always remains clean baseline`() {
        assertFalse(
            GuardianPowerPolicy.decide(
                base.copy(
                    profile = GuardianRuntimeProfile.ADB_PASSIVE
                )
            ).holdCpuLock
        )
    }

    @Test
    fun `inactive or stale service never holds cpu lock`() {
        assertFalse(
            GuardianPowerPolicy.decide(
                base.copy(guardianActive = false)
            ).holdCpuLock
        )
        assertFalse(
            GuardianPowerPolicy.decide(
                base.copy(currentService = false)
            ).holdCpuLock
        )
    }

    @Test
    fun `lab permanent lock has priority`() {
        val lab = base.copy(
            profile = GuardianRuntimeProfile.LAB_EXTREME,
            screenInteractive = true,
            screenOffCpuGuard = false,
            labPermanentCpuLock = true
        )

        assertTrue(
            GuardianPowerPolicy.decide(lab).holdCpuLock
        )
        assertFalse(
            GuardianPowerPolicy.decide(
                lab.copy(labPermanentCpuLock = false)
            ).holdCpuLock
        )
    }
}
'@

    Write-Utf8NoBom $powerTestPath $powerTest

    $profileTestPath = Join-Path $ProjectRoot `
        "app\src\test\java\com\yubegreen\luonnotar\service\GuardianProfilePolicyTest.kt"

    $profileTest = @'
package com.yubegreen.luonnotar.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianProfilePolicyTest {
    @Test
    fun `vivo and iqoo default to cooperative profile`() {
        assertTrue(
            GuardianProfilePolicy.isVivoFamily(
                "vivo",
                "vivo"
            )
        )
        assertTrue(
            GuardianProfilePolicy.isVivoFamily(
                "unknown",
                "iQOO"
            )
        )
        assertEquals(
            GuardianRuntimeProfile.IQOO_COOPERATIVE,
            GuardianProfilePolicy.defaultProfile(true)
        )
    }

    @Test
    fun `iqoo defaults to screen off guard without wifi lock`() {
        val defaults = GuardianProfilePolicy.defaults(
            GuardianRuntimeProfile.IQOO_COOPERATIVE
        )

        assertFalse(defaults.permanentCpuLock)
        assertTrue(defaults.screenOffCpuGuard)
        assertTrue(defaults.scopedCpuLock)
        assertFalse(defaults.highPerfWifiLock)
        assertFalse(defaults.screenEventProbe)
        assertFalse(defaults.periodicDns)
        assertFalse(defaults.periodicHttps)
        assertFalse(defaults.automaticMtalk)
        assertFalse(defaults.frequentNotificationRefresh)
    }

    @Test
    fun `standard keeps manual screen off guard disabled by default`() {
        val defaults = GuardianProfilePolicy.defaults(
            GuardianRuntimeProfile.STANDARD
        )

        assertFalse(defaults.screenOffCpuGuard)
        assertTrue(defaults.scopedCpuLock)
        assertTrue(defaults.periodicDns)
        assertTrue(defaults.periodicHttps)
    }

    @Test
    fun `adb passive disables every active experiment`() {
        val defaults = GuardianProfilePolicy.defaults(
            GuardianRuntimeProfile.ADB_PASSIVE
        )

        assertFalse(defaults.permanentCpuLock)
        assertFalse(defaults.screenOffCpuGuard)
        assertFalse(defaults.scopedCpuLock)
        assertFalse(defaults.highPerfWifiLock)
        assertFalse(defaults.screenEventProbe)
        assertFalse(defaults.periodicDns)
        assertFalse(defaults.periodicHttps)
        assertFalse(defaults.automaticMtalk)
        assertFalse(defaults.frequentNotificationRefresh)
    }

    @Test
    fun `passive profile uses independent sixty second window`() {
        assertFalse(
            GuardianPassiveWindowPolicy.shouldClose(
                100_000L,
                159_999L
            )
        )
        assertTrue(
            GuardianPassiveWindowPolicy.shouldClose(
                100_000L,
                160_000L
            )
        )
        assertFalse(
            GuardianPassiveWindowPolicy.shouldClose(
                0L,
                999_999L
            )
        )
    }

    @Test
    fun `lab levels do not silently enable screen guard`() {
        val level0 = GuardianProfilePolicy.labLevel(0)
        val level1 = GuardianProfilePolicy.labLevel(1)
        val level2 = GuardianProfilePolicy.labLevel(2)
        val level3 = GuardianProfilePolicy.labLevel(3)
        val level4 = GuardianProfilePolicy.labLevel(4)

        assertFalse(level0.scopedCpuLock)
        assertTrue(level1.scopedCpuLock)
        assertTrue(level2.screenEventProbe)
        assertTrue(level3.periodicDns)
        assertTrue(level3.periodicHttps)
        assertTrue(level4.permanentCpuLock)
        assertTrue(level4.highPerfWifiLock)
        assertFalse(level4.screenOffCpuGuard)
        assertFalse(level4.automaticMtalk)
    }

    @Test
    fun `quiet window ends exactly at deadline`() {
        assertTrue(
            GuardianProfilePolicy.quietWindowActive(
                119_999L,
                120_000L
            )
        )
        assertFalse(
            GuardianProfilePolicy.quietWindowActive(
                120_000L,
                120_000L
            )
        )
    }

    @Test
    fun `notification refresh defaults to ten minutes`() {
        val cooperative = GuardianProfilePolicy.defaults(
            GuardianRuntimeProfile.IQOO_COOPERATIVE
        )

        assertEquals(
            10 * 60_000L,
            GuardianProfilePolicy
                .notificationRefreshInterval(cooperative)
        )
        assertEquals(
            60_000L,
            GuardianProfilePolicy.notificationRefreshInterval(
                cooperative.copy(
                    frequentNotificationRefresh = true
                )
            )
        )
    }
}
'@

    Write-Utf8NoBom $profileTestPath $profileTest

    # 9. 政策版本及说明
    $policyManagerPath = Join-Path $ProjectRoot `
        "app\src\main\java\com\yubegreen\luonnotar\policy\PolicyManager.kt"

    $policyManager = Read-NormalizedText $policyManagerPath
    $policyManager = Replace-ExactlyOnce `
        $policyManager `
        'const val VERSION = "1.2"' `
        'const val VERSION = "1.3"' `
        "升级政策版本"

    Write-Utf8NoBom $policyManagerPath $policyManager

    $policyTextPath = Join-Path $ProjectRoot `
        "app\src\main\res\raw\luonnotar_policy_zh.txt"

    $policyText = Read-NormalizedText $policyTextPath
    $policyText = Replace-ExactlyOnce `
        $policyText `
        '政策版本：1.2' `
        '政策版本：1.3' `
        "更新政策正文版本"

    $policyText = Replace-ExactlyOnce $policyText @'
vivo/iQOO 自适应可靠性模式会在守护开启且屏幕关闭时持续持有 CPU WakeLock，并在屏幕点亮、守护暂停或停止时释放；该模式不长期使用 Wi-Fi 高性能锁；
永久锁、高性能 Wi-Fi、屏幕事件探测与高频网络探测只可由用户在实验室模式中逐项启用；
'@ @'
用户可在任何厂商设备上手动开启“熄屏 CPU Guard”；它仅在守护开启且屏幕关闭时持续持有 CPU WakeLock，并在屏幕点亮、守护暂停或停止时释放，不会自动开启 Wi-Fi 高性能锁；
全天永久 CPU Lock、高性能 Wi-Fi、屏幕事件探测与高频网络探测只可由用户在实验室模式中逐项启用；
'@ "更新跨厂商 CPU Guard 政策说明"

    Write-Utf8NoBom $policyTextPath $policyText

    # 10. 更新测试脚本版本检查
    $toolPath = Join-Path $ProjectRoot `
        "tools\test-iqoo-aggressive.ps1"

    if (Test-Path -LiteralPath $toolPath -PathType Leaf) {
        $tool = Read-NormalizedText $toolPath
        $tool = $tool.Replace(
            "versionCode=26(?:\s|$)",
            "versionCode=27(?:\s|$)"
        )
        $tool = $tool.Replace(
            "versionName=1\.6\.9(?:\s|$)",
            "versionName=1\.7\.0(?:\s|$)"
        )
        $tool = $tool.Replace(
            "Luonnotar 1.6.9 (versionCode 26)",
            "Luonnotar 1.7.0 (versionCode 27)"
        )
        Write-Utf8NoBom $toolPath $tool
    }

    # 11. Changelog
    $changelogPath = Join-Path $ProjectRoot `
        "docs\changelog-1.7.0.md"

    $changelog = @'
# Luonnotar 1.7.0

- Added a user-visible Screen-off CPU Guard switch.
- The switch works across manufacturers and in STANDARD mode.
- iQOO cooperative mode enables Screen-off CPU Guard by default.
- STANDARD mode keeps it disabled by default for clean A/B testing.
- ADB passive verification always ignores continuous CPU locks.
- Screen-off CPU Guard never enables the high-performance Wi-Fi lock.
- Added an independent persisted status for the continuous WakeLock.
- The dashboard now shows configuration, screen state, and actual continuous-lock state.
- Added STANDARD to the runtime-profile selector.
- Preserved the separate scoped 10-second CPU WakeLock.
- Preserved all 1.6.8 and 1.6.9 lifecycle, generation, cleanup, and rebind fixes.
'@

    Write-Utf8NoBom $changelogPath $changelog
}
catch {
    foreach ($relative in $backupTargets) {
        $backup = Join-Path $backupRoot $relative
        $destination = Join-Path $ProjectRoot $relative

        if (Test-Path -LiteralPath $backup -PathType Leaf) {
            New-Item `
                -ItemType Directory `
                -Path (Split-Path -Parent $destination) `
                -Force |
                Out-Null

            Copy-Item `
                -LiteralPath $backup `
                -Destination $destination `
                -Force
        }
    }

    $created = Join-Path $ProjectRoot `
        "docs\changelog-1.7.0.md"

    if (Test-Path -LiteralPath $created -PathType Leaf) {
        Remove-Item -LiteralPath $created -Force
    }

    Write-Host ""
    Write-Host "补丁失败，已从备份恢复原文件。" `
        -ForegroundColor Red

    throw
}

Write-Host ""
Write-Host "Luonnotar 1.7.0 补丁已应用。" `
    -ForegroundColor Green
Write-Host "版本：1.7.0 / versionCode 27"
Write-Host "备份目录：$backupRoot"
Write-Host ""
Write-Host "新增："
Write-Host "  - 主界面独立熄屏 CPU Guard 开关"
Write-Host "  - STANDARD 跨厂商支持"
Write-Host "  - 连续 CPU Lock 真实状态"
Write-Host "  - ADB 一次性验证强制无连续锁"
Write-Host ""
Write-Host "未联动：高性能 Wi-Fi Lock、DNS、HTTPS、mtalk、Binder Anchor"
Write-Host ""
Write-Host "下一步先运行："
Write-Host '.\gradlew.bat testDebugUnitTest --stacktrace 2>&1 | Tee-Object -FilePath .\test-1.7.0.txt'