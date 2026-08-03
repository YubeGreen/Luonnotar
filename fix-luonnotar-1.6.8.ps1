[CmdletBinding()]
param(
    [string]$ProjectRoot = (Get-Location).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$Timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$BackupRoot = Join-Path $ProjectRoot "manual-backup-$Timestamp"
New-Item -ItemType Directory -Path $BackupRoot -Force | Out-Null

function Get-ProjectFile {
    param([Parameter(Mandatory)][string]$RelativePath)
    $path = Join-Path $ProjectRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "File not found: $path"
    }
    return $path
}

function Backup-ProjectFile {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$RelativePath
    )
    $destination = Join-Path $BackupRoot $RelativePath
    $directory = Split-Path -Parent $destination
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    Copy-Item -LiteralPath $Path -Destination $destination -Force
}

function Replace-Exact {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Old,
        [Parameter(Mandatory)][string]$New,
        [Parameter(Mandatory)][string]$Label
    )

    $raw = [System.IO.File]::ReadAllText($Path)
    $useCrLf = $raw.Contains("`r`n")
    $text = $raw.Replace("`r`n", "`n")
    $oldNormalized = $Old.Replace("`r`n", "`n")
    $newNormalized = $New.Replace("`r`n", "`n")
    $count = ([regex]::Matches($text, [regex]::Escape($oldNormalized))).Count

    if ($count -ne 1) {
        throw "[$Label] expected exactly one match, found $count in $Path. No partial write was performed for this replacement."
    }

    $updated = $text.Replace($oldNormalized, $newNormalized)
    if ($useCrLf) {
        $updated = $updated.Replace("`n", "`r`n")
    }
    [System.IO.File]::WriteAllText($Path, $updated, $Utf8NoBom)
    Write-Host "OK  $Label"
}

$cleanupRelative = 'app\src\main\java\com\yubegreen\luonnotar\receiver\GuardianCleanupReceiver.kt'
$listenerRelative = 'app\src\main\java\com\yubegreen\luonnotar\notification\ArrivalNotificationListener.kt'
$mainRelative = 'app\src\main\java\com\yubegreen\luonnotar\MainActivity.kt'
$testScriptRelative = 'tools\test-iqoo-aggressive.ps1'

$cleanupPath = Get-ProjectFile $cleanupRelative
$listenerPath = Get-ProjectFile $listenerRelative
$mainPath = Get-ProjectFile $mainRelative
$testScriptPath = Get-ProjectFile $testScriptRelative

Backup-ProjectFile $cleanupPath $cleanupRelative
Backup-ProjectFile $listenerPath $listenerRelative
Backup-ProjectFile $mainPath $mainRelative
Backup-ProjectFile $testScriptPath $testScriptRelative

Replace-Exact -Path $cleanupPath -Label 'Guardian cleanup treats provider failure as UNKNOWN' -Old @'
                            val status = GuardianStatusClient.status(appContext)
                            val enabled = status?.getBoolean(
                                LuonnotarPreferences.KEY_ENABLED,
                                false
                            ) ?: false
                            val paused = status?.getBoolean(
                                LuonnotarPreferences.KEY_PAUSED,
                                false
                            ) ?: false
                            val allowed = if (intent.action == ACTION_CLEANUP_DISABLED) {
'@ -New @'
                            val status = GuardianStatusClient.status(appContext)
                            if (status == null) {
                                LogManager.event(
                                    appContext,
                                    "cleanup_status_unknown",
                                    mapOf("action" to intent.action)
                                )
                                return@execute
                            }
                            val enabled = status.getBoolean(
                                LuonnotarPreferences.KEY_ENABLED,
                                false
                            )
                            val paused = status.getBoolean(
                                LuonnotarPreferences.KEY_PAUSED,
                                false
                            )
                            val allowed = if (intent.action == ACTION_CLEANUP_DISABLED) {
'@

Replace-Exact -Path $listenerPath -Label 'Track whether a rebind callback is already scheduled' -Old @'
    private var rebindAttempt = 0
    private var rebindRequestWatchdog: Runnable? = null
    private val rebindRunnable = Runnable {
        if (
'@ -New @'
    private var rebindAttempt = 0
    private var rebindScheduled = false
    private var rebindRequestWatchdog: Runnable? = null
    private val rebindRunnable = Runnable {
        rebindScheduled = false
        if (
'@

Replace-Exact -Path $listenerPath -Label 'Clear watchdog ownership before scheduling the next rebind' -Old @'
            rebindRequestWatchdog = Runnable {
                if (!destroying && !listenerConnected && notificationAccessGranted()) {
                    rebindAttempt++
                    scheduleRebind()
                }
            }
'@ -New @'
            rebindRequestWatchdog = Runnable {
                rebindRequestWatchdog = null
                if (!destroying && !listenerConnected && notificationAccessGranted()) {
                    rebindAttempt++
                    scheduleRebind()
                }
            }
'@

Replace-Exact -Path $listenerPath -Label 'Reset scheduled flag when listener connects' -Old @'
        listenerConnected = true
        rebindHandler.removeCallbacks(rebindRunnable)
        rebindHandler.removeCallbacks(heartbeatRunnable)
'@ -New @'
        listenerConnected = true
        rebindHandler.removeCallbacks(rebindRunnable)
        rebindScheduled = false
        rebindHandler.removeCallbacks(heartbeatRunnable)
'@

Replace-Exact -Path $listenerPath -Label 'Reset scheduled flag when listener disconnects' -Old @'
        LogManager.event(this, "notification_listener_disconnected")
        rebindHandler.removeCallbacks(rebindRunnable)
        rebindHandler.removeCallbacks(heartbeatRunnable)
'@ -New @'
        LogManager.event(this, "notification_listener_disconnected")
        rebindHandler.removeCallbacks(rebindRunnable)
        rebindScheduled = false
        rebindHandler.removeCallbacks(heartbeatRunnable)
'@

Replace-Exact -Path $listenerPath -Label 'Reset scheduled flag during listener destruction' -Old @'
        destroying = true
        rebindHandler.removeCallbacks(rebindRunnable)
        rebindRequestWatchdog?.let(rebindHandler::removeCallbacks)
'@ -New @'
        destroying = true
        rebindHandler.removeCallbacks(rebindRunnable)
        rebindScheduled = false
        rebindRequestWatchdog?.let(rebindHandler::removeCallbacks)
'@

Replace-Exact -Path $listenerPath -Label 'Prevent duplicate rebind and watchdog scheduling' -Old @'
    private fun scheduleRebind() {
        if (destroying || listenerConnected || !notificationAccessGranted()) return
        if (rebindAttempt > MAX_REBIND_FAILURES) {
            LogManager.event(this, "notification_listener_rebind_exhausted", mapOf("attempts" to rebindAttempt))
            return
        }
        rebindHandler.removeCallbacks(rebindRunnable)
        val delay = REBIND_DELAYS_MS[(rebindAttempt - 1).coerceIn(0, REBIND_DELAYS_MS.lastIndex)]
        rebindHandler.postDelayed(rebindRunnable, delay)
        LogManager.event(this, "notification_listener_rebind_scheduled", mapOf("attempt" to rebindAttempt, "delayMs" to delay))
    }
'@ -New @'
    private fun scheduleRebind() {
        if (destroying || listenerConnected || !notificationAccessGranted()) return
        if (rebindScheduled || rebindRequestWatchdog != null) return
        if (rebindAttempt > MAX_REBIND_FAILURES) {
            LogManager.event(
                this,
                "notification_listener_rebind_exhausted",
                mapOf("attempts" to rebindAttempt)
            )
            return
        }
        val delay = REBIND_DELAYS_MS[
            (rebindAttempt - 1).coerceIn(0, REBIND_DELAYS_MS.lastIndex)
        ]
        rebindScheduled = true
        rebindHandler.postDelayed(rebindRunnable, delay)
        LogManager.event(
            this,
            "notification_listener_rebind_scheduled",
            mapOf("attempt" to rebindAttempt, "delayMs" to delay)
        )
    }
'@

Replace-Exact -Path $listenerPath -Label 'Use 15-minute fourth and fifth rebind delays' -Old @'
        private val REBIND_DELAYS_MS =
            longArrayOf(30_000L, 60_000L, 300_000L, 300_000L, 300_000L)
'@ -New @'
        private val REBIND_DELAYS_MS =
            longArrayOf(30_000L, 60_000L, 300_000L, 900_000L, 900_000L)
'@

Replace-Exact -Path $mainPath -Label 'Use active GMS target state in dashboard routing policy' -Old @'
                        isInstalled("com.google.android.gms"),
'@ -New @'
                        isActiveUserTarget("com.google.android.gms"),
'@

Replace-Exact -Path $mainPath -Label 'Use active WhatsApp target state in dashboard routing policy' -Old @'
                        isInstalled("com.whatsapp"),
'@ -New @'
                        isActiveUserTarget("com.whatsapp"),
'@

Replace-Exact -Path $mainPath -Label 'Use active WhatsApp Business target state in dashboard routing policy' -Old @'
                        isInstalled("com.whatsapp.w4b"),
'@ -New @'
                        isActiveUserTarget("com.whatsapp.w4b"),
'@

Replace-Exact -Path $mainPath -Label 'Add shared active-target semantics to MainActivity' -Old @'
    private fun isInstalled(target: String): Boolean = try {
        packageManager.getApplicationInfo(target, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun environmentEvidence(): Pair<List<SupportedVpnProvider>, FcmHealthEvidence> {
'@ -New @'
    private fun isInstalled(target: String): Boolean = try {
        packageManager.getApplicationInfo(target, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun isActiveUserTarget(target: String): Boolean {
        if (Process.myUid() / 100_000 != 0) return false
        return runCatching {
            @Suppress("DEPRECATION")
            val info = packageManager.getApplicationInfo(
                target,
                PackageManager.MATCH_DISABLED_COMPONENTS
            )
            info.enabled &&
                (info.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) == 0 &&
                (info.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) == 0
        }.getOrDefault(false)
    }

    private fun environmentEvidence(): Pair<List<SupportedVpnProvider>, FcmHealthEvidence> {
'@

Replace-Exact -Path $testScriptPath -Label 'Use the 1.6.8 release APK by default' -Old @'
    $ApkPath = Join-Path $PSScriptRoot "..\Luonnotar-1.6.7-YubeGreen-release.apk"
'@ -New @'
    $ApkPath = Join-Path $PSScriptRoot "..\Luonnotar-1.6.8-YubeGreen-release.apk"
'@

Replace-Exact -Path $testScriptPath -Label 'Validate versionCode 25' -Old @'
            $installedVersion.Output -notmatch "versionCode=24(?:\s|$)" -or
'@ -New @'
            $installedVersion.Output -notmatch "versionCode=25(?:\s|$)" -or
'@

Replace-Exact -Path $testScriptPath -Label 'Validate versionName 1.6.8' -Old @'
            $installedVersion.Output -notmatch "versionName=1\.6\.7(?:\s|$)"
'@ -New @'
            $installedVersion.Output -notmatch "versionName=1\.6\.8(?:\s|$)"
'@

Replace-Exact -Path $testScriptPath -Label 'Update SkipInstall error message' -Old @'
            throw "SkipInstall requires Luonnotar 1.6.7 (versionCode 24) to be installed."
'@ -New @'
            throw "SkipInstall requires Luonnotar 1.6.8 (versionCode 25) to be installed."
'@

Write-Host ''
Write-Host 'Patch completed successfully.' -ForegroundColor Green
Write-Host "Backup directory: $BackupRoot"
Write-Host ''
Write-Host 'Next commands:'
Write-Host '  .\gradlew.bat testDebugUnitTest --stacktrace'
Write-Host '  .\gradlew.bat lintDebug'
Write-Host '  .\gradlew.bat assembleDebug'
