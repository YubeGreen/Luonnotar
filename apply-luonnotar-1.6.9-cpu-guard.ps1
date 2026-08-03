param(
    [string]$ProjectRoot = (Get-Location).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot = [System.IO.Path]::GetFullPath($ProjectRoot)
$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Read-NormalizedText {
    param([Parameter(Mandatory = $true)][string]$Path)
    return [System.IO.File]::ReadAllText($Path).Replace("`r`n", "`n").Replace("`r", "`n")
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
    $count = [regex]::Matches($Text, [regex]::Escape($Old)).Count
    if ($count -ne 1) {
        throw "$Description：预期匹配 1 次，实际匹配 $count 次。已停止，未继续猜测修改。"
    }
    return $Text.Replace($Old, $New)
}

$required = @(
    "gradlew.bat",
    "app\build.gradle.kts",
    "app\src\main\java\com\yubegreen\luonnotar\service\FcmGuardianService.kt",
    "app\src\main\java\com\yubegreen\luonnotar\MainActivity.kt",
    "app\src\main\java\com\yubegreen\luonnotar\policy\PolicyManager.kt",
    "app\src\main\res\raw\luonnotar_policy_zh.txt"
)
foreach ($relative in $required) {
    $full = Join-Path $ProjectRoot $relative
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
        throw "缺少必需文件：$full"
    }
}

$buildFile = Join-Path $ProjectRoot "app\build.gradle.kts"
$buildBefore = Read-NormalizedText $buildFile
if ($buildBefore -notmatch 'versionCode\s*=\s*25' -or
    $buildBefore -notmatch 'versionName\s*=\s*"1\.6\.8"') {
    throw "当前工程不是预期的 1.6.8 / versionCode 25。不要对未知版本强行套补丁。"
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupRoot = Join-Path $ProjectRoot "manual-backup-1.6.9-cpu-guard-$timestamp"
$backupTargets = @(
    "app\build.gradle.kts",
    "app\src\main\java\com\yubegreen\luonnotar\service\FcmGuardianService.kt",
    "app\src\main\java\com\yubegreen\luonnotar\MainActivity.kt",
    "app\src\main\java\com\yubegreen\luonnotar\policy\PolicyManager.kt",
    "app\src\main\res\raw\luonnotar_policy_zh.txt",
    "tools\test-iqoo-aggressive.ps1"
)
foreach ($relative in $backupTargets) {
    $source = Join-Path $ProjectRoot $relative
    if (Test-Path -LiteralPath $source -PathType Leaf) {
        $destination = Join-Path $backupRoot $relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination -Force
    }
}

try {
$fcmPath = Join-Path $ProjectRoot "app\src\main\java\com\yubegreen\luonnotar\service\FcmGuardianService.kt"
$fcm = Read-NormalizedText $fcmPath

$fcm = Replace-ExactlyOnce $fcm @'
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
'@ @'
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var scopedWakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
'@ "增加独立 scoped WakeLock"

$fcm = Replace-ExactlyOnce $fcm @'
            if (
                runtime.experiments.screenEventProbe &&
                isActivelyEnabled(prefs)
            ) {
'@ @'
            reconcileCpuLockPolicy(
                reason = timelineEvent,
                screenInteractiveOverride =
                    action != Intent.ACTION_SCREEN_OFF
            )
            if (
                runtime.experiments.screenEventProbe &&
                isActivelyEnabled(prefs)
            ) {
'@ "屏幕事件即时重算 CPU Guard"

$fcm = Replace-ExactlyOnce $fcm @'
            val tickSeconds = if (runtimeSettings().cooperative) {
                30L
            } else {
                TICK_SECONDS
            }
'@ @'
            val tickSeconds = if (runtimeSettings().cooperative) {
                GuardianPowerPolicy.IQOO_TICK_SECONDS
            } else {
                TICK_SECONDS
            }
'@ "统一 iQOO tick 常量"

$fcm = Replace-ExactlyOnce $fcm @'
        if (nowElapsed - lastLockCheckElapsed >= LOCK_CHECK_MS) {
            lastLockCheckElapsed = nowElapsed
            vpnEvidence = vpnMonitor.current()
            networkEvidence = networkMonitor.current()
            observeVpnPolicySettings()
        }
'@ @'
        if (nowElapsed - lastLockCheckElapsed >= LOCK_CHECK_MS) {
            lastLockCheckElapsed = nowElapsed
            vpnEvidence = vpnMonitor.current()
            networkEvidence = networkMonitor.current()
            observeVpnPolicySettings()
            reconcileCpuLockPolicy("periodic_lock_check")
            reconcileWifiLock()
        }
'@ "30 秒锁策略自愈"

$fcm = Replace-ExactlyOnce $fcm @'
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:guardian_cpu").apply {
            setReferenceCounted(false)
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
'@ @'
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:guardian_cpu_continuous"
        ).apply {
            setReferenceCounted(false)
        }
        scopedWakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:guardian_cpu_scoped"
        ).apply {
            setReferenceCounted(false)
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
'@ "拆分连续与短时 CPU WakeLock"

$oldCpuPolicy = @'
    @Synchronized
    private fun reconcileCpuLockPolicy(reason: String) {
        if (!::wakeLock.isInitialized) return
        val settings = runtimeSettings()
        val shouldHold =
            isActivelyEnabled() &&
                isCurrentServiceInstance() &&
                settings.profile == GuardianRuntimeProfile.LAB_EXTREME &&
                settings.experiments.permanentCpuLock
        if (shouldHold && !wakeLock.isHeld) {
            wakeLock.acquire()
            LuonnotarPreferences.deviceProtected(this).edit()
                .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, true).apply()
            LogManager.event(
                this,
                "wake_lock_acquired",
                mapOf("reason" to reason, "scope" to "lab_permanent")
            )
            LogManager.timeline(
                this,
                "wake_lock_state_changed",
                mapOf(
                    "wakeLockHeld" to true,
                    "lockChangeReason" to reason,
                    "scope" to "lab_permanent"
                )
            )
        } else if (!shouldHold && wakeLock.isHeld) {
            wakeLock.release()
            LuonnotarPreferences.deviceProtected(this).edit()
                .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, false).apply()
            LogManager.timeline(
                this,
                "wake_lock_state_changed",
                mapOf(
                    "wakeLockHeld" to false,
                    "lockChangeReason" to reason,
                    "scope" to "policy_release"
                )
            )
        }
    }
'@
$newCpuPolicy = @'
    @Synchronized
    private fun reconcileCpuLockPolicy(
        reason: String,
        screenInteractiveOverride: Boolean? = null
    ) {
        if (!::wakeLock.isInitialized) return
        val settings = runtimeSettings()
        val powerManager = getSystemService(PowerManager::class.java)
        val screenInteractive =
            screenInteractiveOverride ?: powerManager.isInteractive
        val decision = GuardianPowerPolicy.decide(
            GuardianPowerInput(
                guardianActive = isActivelyEnabled(),
                currentService = isCurrentServiceInstance(),
                profile = settings.profile,
                screenInteractive = screenInteractive,
                labPermanentCpuLock =
                    settings.experiments.permanentCpuLock
            )
        )
        if (decision.holdCpuLock && !wakeLock.isHeld) {
            wakeLock.acquire()
            LuonnotarPreferences.deviceProtected(this).edit()
                .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, true).apply()
            LogManager.event(
                this,
                if (settings.cooperative) {
                    "iqoo_cpu_guard_acquired"
                } else {
                    "wake_lock_acquired"
                },
                mapOf(
                    "reason" to reason,
                    "scope" to decision.scope
                )
            )
            LogManager.timeline(
                this,
                "wake_lock_state_changed",
                mapOf(
                    "wakeLockHeld" to true,
                    "continuousWakeLockHeld" to true,
                    "scopedWakeLockHeld" to
                        (::scopedWakeLock.isInitialized &&
                            scopedWakeLock.isHeld),
                    "lockChangeReason" to reason,
                    "scope" to decision.scope,
                    "screenInteractive" to screenInteractive
                )
            )
        } else if (!decision.holdCpuLock && wakeLock.isHeld) {
            wakeLock.release()
            val anyCpuLockHeld =
                ::scopedWakeLock.isInitialized && scopedWakeLock.isHeld
            LuonnotarPreferences.deviceProtected(this).edit()
                .putBoolean(
                    LuonnotarPreferences.KEY_WAKE_LOCK,
                    anyCpuLockHeld
                )
                .apply()
            LogManager.event(
                this,
                if (settings.cooperative) {
                    "iqoo_cpu_guard_released"
                } else {
                    "wake_lock_released"
                },
                mapOf(
                    "reason" to reason,
                    "scope" to decision.scope
                )
            )
            LogManager.timeline(
                this,
                "wake_lock_state_changed",
                mapOf(
                    "wakeLockHeld" to anyCpuLockHeld,
                    "continuousWakeLockHeld" to false,
                    "scopedWakeLockHeld" to anyCpuLockHeld,
                    "lockChangeReason" to reason,
                    "scope" to decision.scope,
                    "screenInteractive" to screenInteractive
                )
            )
        }
    }
'@
$fcm = Replace-ExactlyOnce $fcm $oldCpuPolicy $newCpuPolicy "替换连续 CPU Guard 策略"

$oldScoped = @'
    private inline fun <T> withScopedCpuLock(
        reason: String,
        block: () -> T
    ): T {
        val settings = runtimeSettings()
        if (
            !settings.experiments.scopedCpuLock ||
            settings.experiments.permanentCpuLock &&
            settings.profile == GuardianRuntimeProfile.LAB_EXTREME
        ) {
            return block()
        }
        var acquired = false
        synchronized(this) {
            if (
                isActivelyEnabled() &&
                isCurrentServiceInstance() &&
                !wakeLock.isHeld
            ) {
                wakeLock.acquire(
                    GuardianProfilePolicy.SCOPED_CPU_LOCK_TIMEOUT_MS
                )
                acquired = true
                LuonnotarPreferences.deviceProtected(this).edit()
                    .putBoolean(
                        LuonnotarPreferences.KEY_WAKE_LOCK,
                        true
                    )
                    .apply()
                LogManager.timeline(
                    this,
                    "wake_lock_state_changed",
                    mapOf(
                        "wakeLockHeld" to true,
                        "lockChangeReason" to reason,
                        "scope" to "scoped_10s"
                    )
                )
            }
        }
        return try {
            block()
        } finally {
            if (acquired) {
                synchronized(this) {
                    if (wakeLock.isHeld) wakeLock.release()
                    LuonnotarPreferences.deviceProtected(this).edit()
                        .putBoolean(
                            LuonnotarPreferences.KEY_WAKE_LOCK,
                            false
                        )
                        .apply()
                    LogManager.timeline(
                        this,
                        "wake_lock_state_changed",
                        mapOf(
                            "wakeLockHeld" to false,
                            "lockChangeReason" to reason,
                            "scope" to "scoped_complete"
                        )
                    )
                }
            }
        }
    }
'@
$newScoped = @'
    private inline fun <T> withScopedCpuLock(
        reason: String,
        block: () -> T
    ): T {
        val settings = runtimeSettings()
        if (!settings.experiments.scopedCpuLock) {
            return block()
        }
        var acquired = false
        synchronized(this) {
            if (
                isActivelyEnabled() &&
                isCurrentServiceInstance() &&
                !wakeLock.isHeld &&
                !scopedWakeLock.isHeld
            ) {
                scopedWakeLock.acquire(
                    GuardianProfilePolicy.SCOPED_CPU_LOCK_TIMEOUT_MS
                )
                acquired = true
                LuonnotarPreferences.deviceProtected(this).edit()
                    .putBoolean(
                        LuonnotarPreferences.KEY_WAKE_LOCK,
                        true
                    )
                    .apply()
                LogManager.timeline(
                    this,
                    "wake_lock_state_changed",
                    mapOf(
                        "wakeLockHeld" to true,
                        "continuousWakeLockHeld" to false,
                        "scopedWakeLockHeld" to true,
                        "lockChangeReason" to reason,
                        "scope" to "scoped_10s"
                    )
                )
            }
        }
        return try {
            block()
        } finally {
            if (acquired) {
                synchronized(this) {
                    if (scopedWakeLock.isHeld) {
                        scopedWakeLock.release()
                    }
                    val anyCpuLockHeld = wakeLock.isHeld
                    LuonnotarPreferences.deviceProtected(this).edit()
                        .putBoolean(
                            LuonnotarPreferences.KEY_WAKE_LOCK,
                            anyCpuLockHeld
                        )
                        .apply()
                    LogManager.timeline(
                        this,
                        "wake_lock_state_changed",
                        mapOf(
                            "wakeLockHeld" to anyCpuLockHeld,
                            "continuousWakeLockHeld" to
                                wakeLock.isHeld,
                            "scopedWakeLockHeld" to false,
                            "lockChangeReason" to reason,
                            "scope" to "scoped_complete"
                        )
                    )
                }
            }
        }
    }
'@
$fcm = Replace-ExactlyOnce $fcm $oldScoped $newScoped "隔离连续与短时 CPU 锁"

$oldRelease = @'
    @Synchronized
    private fun releaseLocks(reason: String) {
        val wifiWasHeld = ::wifiLock.isInitialized && wifiLock.isHeld
        val wakeWasHeld = ::wakeLock.isInitialized && wakeLock.isHeld
        if (wifiWasHeld) wifiLock.release()
        if (wakeWasHeld) wakeLock.release()
        LuonnotarPreferences.deviceProtected(this).edit()
            .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, false)
            .putBoolean(LuonnotarPreferences.KEY_WIFI_LOCK, false)
            .apply()
        if (wifiWasHeld || wakeWasHeld) {
            LogManager.timeline(
                this,
                "guardian_locks_released",
                mapOf(
                    "wakeLockHeld" to false,
                    "wifiLockHeld" to false,
                    "previousWakeLockHeld" to wakeWasHeld,
                    "previousWifiLockHeld" to wifiWasHeld,
                    "lockChangeReason" to reason
                )
            )
        }
    }
'@
$newRelease = @'
    @Synchronized
    private fun releaseLocks(reason: String) {
        val wifiWasHeld = ::wifiLock.isInitialized && wifiLock.isHeld
        val continuousWakeWasHeld =
            ::wakeLock.isInitialized && wakeLock.isHeld
        val scopedWakeWasHeld =
            ::scopedWakeLock.isInitialized && scopedWakeLock.isHeld
        if (wifiWasHeld) wifiLock.release()
        if (continuousWakeWasHeld) wakeLock.release()
        if (scopedWakeWasHeld) scopedWakeLock.release()
        LuonnotarPreferences.deviceProtected(this).edit()
            .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, false)
            .putBoolean(LuonnotarPreferences.KEY_WIFI_LOCK, false)
            .apply()
        if (
            wifiWasHeld ||
            continuousWakeWasHeld ||
            scopedWakeWasHeld
        ) {
            LogManager.timeline(
                this,
                "guardian_locks_released",
                mapOf(
                    "wakeLockHeld" to false,
                    "wifiLockHeld" to false,
                    "previousWakeLockHeld" to
                        (continuousWakeWasHeld || scopedWakeWasHeld),
                    "previousContinuousWakeLockHeld" to
                        continuousWakeWasHeld,
                    "previousScopedWakeLockHeld" to scopedWakeWasHeld,
                    "previousWifiLockHeld" to wifiWasHeld,
                    "lockChangeReason" to reason
                )
            )
        }
    }
'@
$fcm = Replace-ExactlyOnce $fcm $oldRelease $newRelease "停止时释放两类 CPU 锁"

$fcm = Replace-ExactlyOnce $fcm @'
            .addLine("CPU / Wi-Fi 锁：${yesNo(::wakeLock.isInitialized && wakeLock.isHeld)} / ${yesNo(::wifiLock.isInitialized && wifiLock.isHeld)}")
'@ @'
            .addLine(
                "CPU / Wi-Fi 锁：${
                    yesNo(
                        (::wakeLock.isInitialized && wakeLock.isHeld) ||
                            (::scopedWakeLock.isInitialized &&
                                scopedWakeLock.isHeld)
                    )
                } / ${yesNo(::wifiLock.isInitialized && wifiLock.isHeld)}"
            )
'@ "通知显示合并 CPU 锁状态"

Write-Utf8NoBom $fcmPath $fcm

$powerPolicyPath = Join-Path $ProjectRoot "app\src\main\java\com\yubegreen\luonnotar\service\GuardianPowerPolicy.kt"
$powerPolicy = @'
package com.yubegreen.luonnotar.service

data class GuardianPowerInput(
    val guardianActive: Boolean,
    val currentService: Boolean,
    val profile: GuardianRuntimeProfile,
    val screenInteractive: Boolean,
    val labPermanentCpuLock: Boolean
)

data class GuardianPowerDecision(
    val holdCpuLock: Boolean,
    val scope: String
)

object GuardianPowerPolicy {
    const val IQOO_TICK_SECONDS = 30L

    fun decide(input: GuardianPowerInput): GuardianPowerDecision {
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
        return when (input.profile) {
            GuardianRuntimeProfile.IQOO_COOPERATIVE ->
                GuardianPowerDecision(
                    holdCpuLock = !input.screenInteractive,
                    scope = if (input.screenInteractive) {
                        "iqoo_screen_on"
                    } else {
                        "iqoo_screen_off_continuous"
                    }
                )
            GuardianRuntimeProfile.LAB_EXTREME ->
                GuardianPowerDecision(
                    holdCpuLock = input.labPermanentCpuLock,
                    scope = if (input.labPermanentCpuLock) {
                        "lab_permanent"
                    } else {
                        "lab_permanent_disabled"
                    }
                )
            else ->
                GuardianPowerDecision(
                    holdCpuLock = false,
                    scope = "profile_no_continuous_cpu_lock"
                )
        }
    }
}
'@
Write-Utf8NoBom $powerPolicyPath $powerPolicy

$powerTestPath = Join-Path $ProjectRoot "app\src\test\java\com\yubegreen\luonnotar\service\GuardianPowerPolicyTest.kt"
$powerTest = @'
package com.yubegreen.luonnotar.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianPowerPolicyTest {
    @Test
    fun `iqoo holds cpu lock only while screen is off`() {
        val base = GuardianPowerInput(
            guardianActive = true,
            currentService = true,
            profile = GuardianRuntimeProfile.IQOO_COOPERATIVE,
            screenInteractive = false,
            labPermanentCpuLock = false
        )
        assertTrue(GuardianPowerPolicy.decide(base).holdCpuLock)
        assertFalse(
            GuardianPowerPolicy.decide(
                base.copy(screenInteractive = true)
            ).holdCpuLock
        )
    }

    @Test
    fun `inactive or stale service never holds cpu lock`() {
        val base = GuardianPowerInput(
            guardianActive = true,
            currentService = true,
            profile = GuardianRuntimeProfile.IQOO_COOPERATIVE,
            screenInteractive = false,
            labPermanentCpuLock = false
        )
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
    fun `lab permanent behavior is preserved`() {
        val base = GuardianPowerInput(
            guardianActive = true,
            currentService = true,
            profile = GuardianRuntimeProfile.LAB_EXTREME,
            screenInteractive = true,
            labPermanentCpuLock = true
        )
        assertTrue(GuardianPowerPolicy.decide(base).holdCpuLock)
        assertFalse(
            GuardianPowerPolicy.decide(
                base.copy(labPermanentCpuLock = false)
            ).holdCpuLock
        )
    }

    @Test
    fun `standard and adb passive never use continuous cpu lock`() {
        val base = GuardianPowerInput(
            guardianActive = true,
            currentService = true,
            profile = GuardianRuntimeProfile.STANDARD,
            screenInteractive = false,
            labPermanentCpuLock = false
        )
        assertFalse(GuardianPowerPolicy.decide(base).holdCpuLock)
        assertFalse(
            GuardianPowerPolicy.decide(
                base.copy(profile = GuardianRuntimeProfile.ADB_PASSIVE)
            ).holdCpuLock
        )
    }
}
'@
Write-Utf8NoBom $powerTestPath $powerTest

$mainPath = Join-Path $ProjectRoot "app\src\main\java\com\yubegreen\luonnotar\MainActivity.kt"
$main = Read-NormalizedText $mainPath
$main = Replace-ExactlyOnce $main `
    '"IQOO_COOPERATIVE · 熄屏静默 120 秒 · 短时出手"' `
    '"IQOO_COOPERATIVE · 熄屏 CPU Guard · 静默 120 秒"' `
    "更新运行策略文案"
$main = Replace-ExactlyOnce $main `
    '"已应用 iQOO 协作模式：熄屏先静默 120 秒，默认不持有永久锁、不周期打网"' `
    '"已应用 iQOO 自适应可靠性模式：熄屏持续持有 CPU WakeLock，保留 120 秒网络静默；不长期持有 Wi-Fi 高性能锁、不周期打网"' `
    "更新模式应用提示"
$main = Replace-ExactlyOnce $main `
    '"检测到 vivo/iQOO：\n• 默认使用协作模式：熄屏先静默 120 秒，不永久占用 CPU/Wi‑Fi Lock，不因屏幕事件主动打网\n• 努昂诺塔和当前 VPN（Proton/Tailscale）：允许自启动、后台运行、电池不限制、最近任务锁定、常驻通知\n• i 管家不得自动清理\n• WhatsApp/GMS：允许后台数据与后台运行，GMS 保持 Doze 白名单\n• 实验室 L0–L4 仅用于逐项 A/B；强度升高后若冻结次数或推送延迟增加，应立即降级\n• 原生 Doze 关闭不代表 vivo PEM、QuickFrozen 或后台清理已关闭"' `
    '"检测到 vivo/iQOO：\n• 默认使用自适应可靠性模式：守护开启且熄屏时持续持有 CPU WakeLock，亮屏立即释放；保留 120 秒网络静默，不长期占用 Wi‑Fi 高性能锁\n• 努昂诺塔和当前 VPN（Proton/Tailscale）：允许自启动、后台运行、电池不限制、最近任务锁定、常驻通知\n• i 管家不得自动清理\n• WhatsApp/GMS：允许后台数据与后台运行，GMS 保持 Doze 白名单\n• 实验室 L0–L4 仅用于逐项 A/B；强度升高后若冻结次数或推送延迟增加，应立即降级\n• 原生 Doze 关闭不代表 vivo PEM、QuickFrozen 或后台清理已关闭"' `
    "更新 vivo/iQOO 指引"
Write-Utf8NoBom $mainPath $main

$build = $buildBefore
$build = Replace-ExactlyOnce $build 'versionCode = 25' 'versionCode = 26' "升级 versionCode"
$build = Replace-ExactlyOnce $build 'versionName = "1.6.8"' 'versionName = "1.6.9"' "升级 versionName"
Write-Utf8NoBom $buildFile $build

$policyManagerPath = Join-Path $ProjectRoot "app\src\main\java\com\yubegreen\luonnotar\policy\PolicyManager.kt"
$policyManager = Read-NormalizedText $policyManagerPath
$policyManager = Replace-ExactlyOnce $policyManager `
    'const val VERSION = "1.1"' `
    'const val VERSION = "1.2"' `
    "升级政策版本"
Write-Utf8NoBom $policyManagerPath $policyManager

$policyTextPath = Join-Path $ProjectRoot "app\src\main\res\raw\luonnotar_policy_zh.txt"
$policyText = Read-NormalizedText $policyTextPath
$policyText = Replace-ExactlyOnce $policyText '政策版本：1.1' '政策版本：1.2' "更新政策正文版本"
$policyText = Replace-ExactlyOnce $policyText @'
默认仅在必要任务期间短暂持有 CPU WakeLock，并在任务结束后释放；
vivo/iQOO 默认使用协作模式，不永久持有 CPU WakeLock，不长期使用 Wi-Fi 高性能锁；
'@ @'
默认仅在必要任务期间短暂持有 CPU WakeLock，并在任务结束后释放；
vivo/iQOO 自适应可靠性模式会在守护开启且屏幕关闭时持续持有 CPU WakeLock，并在屏幕点亮、守护暂停或停止时释放；该模式不长期使用 Wi-Fi 高性能锁；
'@ "更新 CPU Guard 政策说明"
Write-Utf8NoBom $policyTextPath $policyText

$testScriptPath = Join-Path $ProjectRoot "tools\test-iqoo-aggressive.ps1"
if (Test-Path -LiteralPath $testScriptPath -PathType Leaf) {
    $testScript = Read-NormalizedText $testScriptPath
    $testScript = $testScript.Replace('versionCode=24(?:\s|$)', 'versionCode=26(?:\s|$)')
    $testScript = $testScript.Replace('versionCode=25(?:\s|$)', 'versionCode=26(?:\s|$)')
    $testScript = $testScript.Replace('versionName=1\.6\.7(?:\s|$)', 'versionName=1\.6\.9(?:\s|$)')
    $testScript = $testScript.Replace('versionName=1\.6\.8(?:\s|$)', 'versionName=1\.6\.9(?:\s|$)')
    $testScript = $testScript.Replace(
        'Luonnotar 1.6.7 (versionCode 24)',
        'Luonnotar 1.6.9 (versionCode 26)'
    )
    $testScript = $testScript.Replace(
        'Luonnotar 1.6.8 (versionCode 25)',
        'Luonnotar 1.6.9 (versionCode 26)'
    )
    Write-Utf8NoBom $testScriptPath $testScript
}

$changeLogPath = Join-Path $ProjectRoot "docs\changelog-1.6.9.md"
$changeLog = @'
# Luonnotar 1.6.9

- iQOO cooperative profile now holds a continuous PARTIAL_WAKE_LOCK only while the screen is off and guardian is active.
- The continuous screen-off lock is released immediately on screen-on, pause, stop, profile change, or service destruction.
- Continuous and scoped CPU locks are separate WakeLock instances so screen transitions cannot release an in-flight scoped task lock, and scoped completion cannot drop the screen-off guard.
- iQOO tick remains 30 seconds.
- No permanent high-performance Wi-Fi lock was added in this build.
- The 120-second screen-off network quiet window remains.
- Periodic DNS, HTTPS, automatic mtalk, and screen-event probes remain disabled by default for iQOO.
- Added pure GuardianPowerPolicy unit tests and explicit iQOO CPU guard timeline events.
'@
Write-Utf8NoBom $changeLogPath $changeLog

} catch {
    foreach ($relative in $backupTargets) {
        $backup = Join-Path $backupRoot $relative
        $destination = Join-Path $ProjectRoot $relative
        if (Test-Path -LiteralPath $backup -PathType Leaf) {
            New-Item -ItemType Directory -Path (
                Split-Path -Parent $destination
            ) -Force | Out-Null
            Copy-Item -LiteralPath $backup -Destination $destination -Force
        }
    }
    @(
        "app\src\main\java\com\yubegreen\luonnotar\service\GuardianPowerPolicy.kt",
        "app\src\test\java\com\yubegreen\luonnotar\service\GuardianPowerPolicyTest.kt",
        "docs\changelog-1.6.9.md"
    ) | ForEach-Object {
        $created = Join-Path $ProjectRoot $_
        if (Test-Path -LiteralPath $created -PathType Leaf) {
            Remove-Item -LiteralPath $created -Force
        }
    }
    Write-Host "补丁失败，已从备份恢复原文件。" -ForegroundColor Red
    throw
}

Write-Host ""
Write-Host "1.6.9 CPU Guard 补丁已应用。" -ForegroundColor Green
Write-Host "备份目录：$backupRoot"
Write-Host "新增：GuardianPowerPolicy.kt、GuardianPowerPolicyTest.kt、changelog-1.6.9.md"
Write-Host "保持不变：30 秒 tick、120 秒静默、不长期持有高性能 Wi-Fi 锁、不周期打网、Binder Anchor。"
Write-Host ""
Write-Host "下一步依次运行："
Write-Host '.\gradlew.bat testDebugUnitTest --stacktrace 2>&1 | Tee-Object -FilePath .\test-1.6.9.txt'
Write-Host '.\gradlew.bat lintDebug 2>&1 | Tee-Object -FilePath .\lint-1.6.9.txt'
Write-Host '.\gradlew.bat assembleRelease --stacktrace 2>&1 | Tee-Object -FilePath .\release-1.6.9.txt'
