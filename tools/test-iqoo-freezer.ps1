[CmdletBinding()]
param(
    [string]$AdbPath = "adb",
    [string]$DeviceSerial = "",
    [string]$OutputRoot = "",
    [ValidateSet("start", "screen_off", "failure", "manual")]
    [string]$Phase = "manual",
    [switch]$DisableAospFreezer,
    [switch]$RestoreAospFreezer
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$captureStarted = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

if ($DisableAospFreezer -and $RestoreAospFreezer) {
    throw "Choose only one of -DisableAospFreezer or -RestoreAospFreezer."
}
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $PSScriptRoot "test-output"
}

$script:Adb = if (Test-Path -LiteralPath $AdbPath -PathType Leaf) {
    (Resolve-Path -LiteralPath $AdbPath).Path
} else {
    (Get-Command $AdbPath -ErrorAction Stop).Source
}
$script:Serial = ""
$targets = @(
    "com.yubegreen.luonnotar",
    "ch.protonvpn.android",
    "com.tailscale.ipn",
    "com.google.android.gms",
    "com.whatsapp",
    "com.whatsapp.w4b"
)

function Invoke-AdbHost {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $oldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $script:Adb @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldPreference
    }
    $text = (($output | ForEach-Object { $_.ToString() }) -join "`n").TrimEnd()
    if ($code -ne 0 -and -not $AllowFailure) {
        throw "adb $($Arguments -join ' ') failed ($code)`n$text"
    }
    [pscustomobject]@{ ExitCode = $code; Output = $text }
}

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    Invoke-AdbHost -Arguments (@("-s", $script:Serial) + $Arguments) `
        -AllowFailure:$AllowFailure
}

function Get-OnlineDevices {
    $result = Invoke-AdbHost -Arguments @("devices", "-l") -AllowFailure
    @(
        foreach ($line in ($result.Output -split "`r?`n")) {
            if ($line -match "^(?<serial>\S+)\s+device(?:\s|$)") {
                $Matches.serial
            }
        }
    )
}

if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
    for ($attempt = 1; $attempt -le 40; $attempt++) {
        $devices = @(Get-OnlineDevices)
        if ($devices.Count -eq 1) {
            $DeviceSerial = $devices[0]
            break
        }
        if ($devices.Count -gt 1) {
            throw "MULTIPLE_DEVICES: $($devices -join ', ')"
        }
        if ($attempt -lt 40) {
            Write-Host "ADB device not visible ($attempt/40); waiting..."
            Start-Sleep -Seconds 5
        }
    }
}
if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
    Write-Host "NO_DEVICE"
    exit 2
}
$script:Serial = $DeviceSerial
$state = Invoke-Adb -Arguments @("get-state") -AllowFailure
if ($state.ExitCode -ne 0 -or $state.Output.Trim() -ne "device") {
    Write-Host "NO_DEVICE: $DeviceSerial"
    exit 2
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$sessionId = "iqoo-freezer-$Phase-$stamp"
$output = Join-Path $OutputRoot $sessionId
New-Item -ItemType Directory -Path $output -Force | Out-Null

function Save-AdbOutput {
    param([string]$Name, [string[]]$Arguments)
    $result = Invoke-Adb -Arguments $Arguments -AllowFailure
    Set-Content -LiteralPath (Join-Path $output $Name) `
        -Value $result.Output -Encoding UTF8
    $result.Output
}

$netpolicyHelp = (Invoke-Adb -Arguments @(
    "shell", "cmd", "netpolicy", "help"
) -AllowFailure).Output
$supportsBackgroundWhitelist =
    $netpolicyHelp -match "restrict-background-whitelist"

$installedTargets = [System.Collections.Generic.List[string]]::new()
foreach ($package in $targets) {
    $path = Invoke-Adb -Arguments @(
        "shell", "pm", "path", $package
    ) -AllowFailure
    if ($path.ExitCode -ne 0 -or $path.Output -notmatch "^package:") {
        Write-Host "NOT_INSTALLED $package"
        continue
    }
    $installedTargets.Add($package)
    Write-Host "TUNING $package"
    Invoke-Adb -Arguments @(
        "shell", "am", "set-inactive", $package, "false"
    ) -AllowFailure | Out-Null
    Invoke-Adb -Arguments @(
        "shell", "am", "set-standby-bucket", $package, "active"
    ) -AllowFailure | Out-Null
    Invoke-Adb -Arguments @(
        "shell", "dumpsys", "deviceidle", "whitelist", "+$package"
    ) -AllowFailure | Out-Null
    foreach ($operation in @(
        "RUN_IN_BACKGROUND",
        "RUN_ANY_IN_BACKGROUND",
        "WAKE_LOCK",
        "START_FOREGROUND"
    )) {
        Invoke-Adb -Arguments @(
            "shell", "cmd", "appops", "set",
            $package, $operation, "allow"
        ) -AllowFailure | Out-Null
    }
    if ($supportsBackgroundWhitelist) {
        $packageLine = (Invoke-Adb -Arguments @(
            "shell", "cmd", "package", "list", "packages",
            "-U", $package
        ) -AllowFailure).Output
        if ($packageLine -match "uid:(?<uid>\d+)") {
            Invoke-Adb -Arguments @(
                "shell", "cmd", "netpolicy", "add",
                "restrict-background-whitelist", $Matches.uid
            ) -AllowFailure | Out-Null
        }
    }
}

$activity = Save-AdbOutput "dumpsys-activity.txt" @(
    "shell", "dumpsys", "activity"
)
$activityProcesses = Save-AdbOutput "dumpsys-activity-processes.txt" @(
    "shell", "dumpsys", "activity", "processes"
)
$power = Save-AdbOutput "dumpsys-power.txt" @(
    "shell", "dumpsys", "power"
)
$deviceIdle = Save-AdbOutput "dumpsys-deviceidle.txt" @(
    "shell", "dumpsys", "deviceidle"
)
$netpolicy = Save-AdbOutput "dumpsys-netpolicy.txt" @(
    "shell", "dumpsys", "netpolicy"
)
$connectivity = Save-AdbOutput "dumpsys-connectivity.txt" @(
    "shell", "dumpsys", "connectivity"
)
$logcat = Save-AdbOutput "logcat.txt" @(
    "logcat", "-d", "-v", "epoch"
)

$combined = @(
    $activity,
    $activityProcesses,
    $power,
    $deviceIdle,
    $netpolicy,
    $connectivity,
    $logcat
) `
    -join "`n"
$freezeLines = @(
    $combined -split "`r?`n" |
        Select-String -Pattern (
            "Apps frozen|mIsFrozen|fast_freezer|QuickFrozen|single-cleaner|" +
            "am_app_frozen|am_app_unfrozen|am_kill|am_proc_start|" +
            "am_uid_stopped|freezing|froze|unfreeze|GCM_HB_ALARM|" +
            "FcmRetry|c2dm|FirebaseInstanceIdReceiver|GcmFGService|" +
            "MessageService|XmppLifecycleWorker|notification_enqueue|" +
            "com\.yubegreen\.luonnotar|ch\.protonvpn\.android|" +
            "com\.tailscale\.ipn|com\.google\.android\.gms|" +
            "com\.whatsapp"
        ) -CaseSensitive:$false |
        ForEach-Object { $_.Line }
)
Set-Content -LiteralPath (Join-Path $output "freeze-summary.txt") `
    -Value $freezeLines -Encoding UTF8

function Test-Frozen {
    param([string]$Package)
    [bool](
        $activityProcesses -split "`r?`n" |
            Select-String -SimpleMatch $Package |
            Select-String -Pattern (
                "mIsFrozen\s*=\s*true|frozen\s*=\s*true"
            ) -CaseSensitive:$false
    )
}

$quickFrozenCount = @(
    $freezeLines |
        Select-String -Pattern "QuickFrozen" -CaseSensitive:$false
).Count
$frozenCount = @(
    $freezeLines |
        Select-String -Pattern (
            "Apps frozen|mIsFrozen\s*=\s*true|freezing|froze"
        ) -CaseSensitive:$false
).Count
$freezerMode = (Invoke-Adb -Arguments @(
    "shell", "device_config", "get",
    "activity_manager_native_boot", "use_freezer"
) -AllowFailure).Output.Trim()

$eventPatterns = [ordered]@{
    fast_freezer = "fast_freezer"
    QuickFrozen = "QuickFrozen"
    single_cleaner = "single-cleaner"
    am_app_frozen = "am_app_frozen"
    am_app_unfrozen = "am_app_unfrozen"
    am_kill = "am_kill"
    am_proc_start = "am_proc_start"
    am_uid_stopped = "am_uid_stopped"
    GCM_HB_ALARM = "GCM_HB_ALARM"
    FcmRetry = "FcmRetry"
    C2DM_RECEIVE = "c2dm.*RECEIVE|RECEIVE.*c2dm"
    FirebaseInstanceIdReceiver = "FirebaseInstanceIdReceiver"
    GcmFGService = "GcmFGService"
    MessageService = "MessageService"
    XmppLifecycleWorker = "XmppLifecycleWorker"
    notification_enqueue = "notification_enqueue"
}
$eventCounts = [ordered]@{}
$timelineEvents = [System.Collections.Generic.List[object]]::new()
$eventSequence = 0L
foreach ($entry in $eventPatterns.GetEnumerator()) {
    $eventCounts[$entry.Key] = @(
        $freezeLines |
            Select-String -Pattern $entry.Value -CaseSensitive:$false
    ).Count
    foreach ($line in @(
        $logcat -split "`r?`n" |
            Select-String -Pattern $entry.Value -CaseSensitive:$false |
            ForEach-Object { $_.Line }
    )) {
        $eventSequence++
        $targetPackage = ""
        foreach ($candidate in $targets) {
            if ($line -match [regex]::Escape($candidate)) {
                $targetPackage = $candidate
                break
            }
        }
        $eventEpoch = if ($line -match "^\s*(?<epoch>\d+\.\d+)") {
            [long]([double]$Matches.epoch * 1000)
        } else {
            -1L
        }
        $lineBytes = [Text.Encoding]::UTF8.GetBytes($line)
        $sha = [Security.Cryptography.SHA256]::Create()
        try {
            $lineHash = (
                [BitConverter]::ToString(
                    $sha.ComputeHash($lineBytes)
                ) -replace "-", ""
            ).ToLowerInvariant()
        } finally {
            $sha.Dispose()
        }
        $timelineEvents.Add([pscustomobject]@{
            epochMs = $eventEpoch
            monotonicMs = -1
            sequence = $eventSequence
            lineHash = $lineHash
            eventType = $entry.Key
            targetPackage = $targetPackage
        })
    }
}
$eventCountsJson = $eventCounts | ConvertTo-Json -Compress
$eventTimelineJson = @(
    $timelineEvents |
        Sort-Object epochMs,monotonicMs,sequence |
        Select-Object -Last 200
) | ConvertTo-Json -Compress
if ($eventTimelineJson.Length -gt 16000) {
    $eventTimelineJson = @(
        $timelineEvents |
            Sort-Object epochMs,monotonicMs,sequence |
            Select-Object -Last 80
    ) | ConvertTo-Json -Compress
}

$targetSnapshots = [System.Collections.Generic.List[object]]::new()
$notificationDump = (Invoke-Adb -Arguments @(
    "shell", "dumpsys", "notification", "--noredact"
) -AllowFailure).Output
$notificationAppSettings = @(
    $notificationDump -split "`r?`n" |
        Select-String -Pattern "AppSettings:" |
        ForEach-Object { $_.Line.Trim() }
)
Set-Content -LiteralPath (
    Join-Path $output "notification-app-settings.txt"
) -Value $notificationAppSettings -Encoding UTF8
foreach ($package in $targets) {
    $installed = $installedTargets.Contains($package)
    if (-not $installed) {
        $targetSnapshots.Add([pscustomobject]@{
            packageName = $package
            uid = -1
            installed = $false
            frozen = $false
            processPresent = $false
            processState = "NOT_INSTALLED"
            backgroundRestricted = $false
            standbyBucket = "UNKNOWN"
            inactive = $false
            netpolicyBlocked = $false
            packageStopped = $false
            packageEnabled = $false
            packageSuspended = $false
            notificationEnabled = $false
            postNotificationsAllowed = $false
            capturedWallTime = (Get-Date).ToString("o")
            commandSupported = "TRUE"
            exitCode = 0
            outputParsed = "TRUE"
            captureError = ""
        })
        continue
    }
    $uidOutput = (Invoke-Adb -Arguments @(
        "shell", "cmd", "package", "list", "packages", "-U", $package
    ) -AllowFailure).Output
    $uid = if ($uidOutput -match "uid:(?<uid>\d+)") {
        [int]$Matches.uid
    } else {
        -1
    }
    $packageDump = (Invoke-Adb -Arguments @(
        "shell", "dumpsys", "package", $package
    ) -AllowFailure).Output
    Set-Content -LiteralPath (
        Join-Path $output "dumpsys-package-$($package.Replace('.', '_')).txt"
    ) -Value $packageDump -Encoding UTF8
    $standby = (Invoke-Adb -Arguments @(
        "shell", "am", "get-standby-bucket", $package
    ) -AllowFailure).Output.Trim()
    $inactiveOutput = (Invoke-Adb -Arguments @(
        "shell", "am", "get-inactive", $package
    ) -AllowFailure).Output
    $appops = (Invoke-Adb -Arguments @(
        "shell", "cmd", "appops", "get", $package
    ) -AllowFailure).Output
    Set-Content -LiteralPath (
        Join-Path $output "appops-$($package.Replace('.', '_')).txt"
    ) -Value $appops -Encoding UTF8
    $processLines = @(
        $activityProcesses -split "`r?`n" |
            Select-String -SimpleMatch $package |
            ForEach-Object { $_.Line }
    )
    $processState = if ($processLines.Count -gt 0) {
        (($processLines[0] -replace "\s+", " ").Trim()).Substring(
            0,
            [Math]::Min(
                48,
                (($processLines[0] -replace "\s+", " ").Trim()).Length
            )
        )
    } else {
        "ABSENT"
    }
    $netpolicyLines = if ($uid -ge 0) {
        @(
            $netpolicy -split "`r?`n" |
                Select-String -Pattern (
                    "(?<!\d)" + $uid + "(?!\d)"
                ) |
                ForEach-Object { $_.Line }
        )
    } else {
        @()
    }
    $userZeroState = @(
        $packageDump -split "`r?`n" |
            Where-Object {
                $_ -match "^\s*User 0:.*installed="
            }
    ) | Select-Object -First 1
    $userZeroStopped =
        $userZeroState -match "\bstopped=true\b"
    $userZeroSuspended =
        $userZeroState -match "\bsuspended=true\b"
    $userZeroEnabledValue = if (
        $userZeroState -match "\benabled=(?<enabled>\d+)\b"
    ) {
        [int]$Matches.enabled
    } else {
        0
    }
    $postNotificationsAllowed = [bool](
        $appops -match "POST_NOTIFICATION:\s*(allow|default)" -or
        $packageDump -match (
            "android\.permission\.POST_NOTIFICATIONS:" +
            "\s*granted=true"
        )
    )
    $notificationSetting = @(
        $notificationAppSettings |
            Where-Object {
                $_ -match (
                    "^AppSettings:\s+" +
                    [regex]::Escape($package) +
                    "\s+\(" + $uid + "\)"
                )
            }
    ) | Select-Object -First 1
    $notificationGloballyEnabled =
        $postNotificationsAllowed -and
        $notificationSetting -notmatch "\bimportance=NONE\b"
    $blockedStateLine = @(
        $netpolicyLines |
            Where-Object {
                $_ -match "blocked_state=.*effective="
            }
    ) | Select-Object -First 1
    $netpolicyEffectivelyBlocked = [bool](
        $blockedStateLine -match "effective=(?<effective>[^}\s]+)" -and
        $Matches.effective -ne "NONE"
    )
    $backgroundRestricted =
        $standby -match "restricted" -or
        $appops -match (
            "(RUN_IN_BACKGROUND|RUN_ANY_IN_BACKGROUND):\s*" +
            "(ignore|deny|errored)"
        )
    $targetSnapshots.Add([pscustomobject]@{
        packageName = $package
        uid = $uid
        installed = $true
        frozen = (Test-Frozen $package)
        processPresent = ($processLines.Count -gt 0)
        processState = $processState
        backgroundRestricted = [bool]$backgroundRestricted
        standbyBucket = $standby
        inactive = if ($inactiveOutput -match "\b(true|false)\b") {
            [bool]($Matches[1] -eq "true")
        } else { "UNKNOWN" }
        netpolicyBlocked = $netpolicyEffectivelyBlocked
        packageStopped = if ($null -ne $userZeroState) {
            $userZeroStopped
        } else { "UNKNOWN" }
        packageEnabled = if ($null -ne $userZeroState) {
            ($userZeroEnabledValue -notin 2, 3, 4)
        } else { "UNKNOWN" }
        packageSuspended = if ($null -ne $userZeroState) {
            $userZeroSuspended
        } else { "UNKNOWN" }
        notificationEnabled = $notificationGloballyEnabled
        postNotificationsAllowed = $postNotificationsAllowed
        capturedWallTime = (Get-Date).ToString("o")
        commandSupported = "TRUE"
        exitCode = 0
        outputParsed = if (
            $uid -ge 0 -and $null -ne $userZeroState
        ) { "TRUE" } else { "UNKNOWN" }
        captureError = if (
            $uid -ge 0 -and $null -ne $userZeroState
        ) { "" } else { "UID_OR_USER0_STATE_UNPARSED" }
    })
}
$targetSnapshotJson = $targetSnapshots | ConvertTo-Json -Compress
$captureFinished = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
Set-Content -LiteralPath (Join-Path $output "target-uid-health.json") `
    -Value $targetSnapshotJson -Encoding UTF8

function ConvertTo-Base64Utf8 {
    param([string]$Value)
    [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes($Value)
    )
}

$broadcast = @(
    "shell", "am", "broadcast",
    "-a", "com.yubegreen.luonnotar.action.ADB_IMPORT_FREEZER",
    "-n", "com.yubegreen.luonnotar/.receiver.AdbFreezerDiagnosticsReceiver",
    "--es", "session_id", $sessionId,
    "--es", "phase", $Phase,
    "--ei", "quickfrozen_count", "$quickFrozenCount",
    "--ei", "frozen_count", "$frozenCount",
    "--ez", "luonnotar_frozen", "$(Test-Frozen 'com.yubegreen.luonnotar')".ToLowerInvariant(),
    "--ez", "tailscale_frozen", "$(Test-Frozen 'com.tailscale.ipn')".ToLowerInvariant(),
    "--ez", "gms_frozen", "$(Test-Frozen 'com.google.android.gms')".ToLowerInvariant(),
    "--ez", "whatsapp_frozen", "$(Test-Frozen 'com.whatsapp')".ToLowerInvariant(),
    "--ez", "whatsapp_business_frozen", "$(Test-Frozen 'com.whatsapp.w4b')".ToLowerInvariant(),
    "--es", "aosp_freezer_mode", $freezerMode,
    "--es", "target_uid_snapshot_b64",
    (ConvertTo-Base64Utf8 $targetSnapshotJson),
    "--es", "event_counts_b64",
    (ConvertTo-Base64Utf8 $eventCountsJson),
    "--es", "event_timeline_b64",
    (ConvertTo-Base64Utf8 $eventTimelineJson),
    "--el", "capture_started", "$captureStarted",
    "--el", "capture_finished", "$captureFinished"
)
$importResult = Invoke-Adb -Arguments $broadcast -AllowFailure
Set-Content -LiteralPath (Join-Path $output "import-result.txt") `
    -Value $importResult.Output -Encoding UTF8
if (
    $importResult.ExitCode -ne 0 -or
    $importResult.Output -notmatch "Broadcast completed"
) {
    throw "ADB_IMPORT_FAILED`n$($importResult.Output)"
}

if ($DisableAospFreezer) {
    Invoke-Adb -Arguments @(
        "shell", "device_config", "put",
        "activity_manager_native_boot", "use_freezer", "false"
    ) | Out-Null
    Write-Host "AOSP_FREEZER_DISABLED; rebooting. This does not disable vivo QuickFrozen."
    Invoke-Adb -Arguments @("reboot") | Out-Null
} elseif ($RestoreAospFreezer) {
    Invoke-Adb -Arguments @(
        "shell", "device_config", "delete",
        "activity_manager_native_boot", "use_freezer"
    ) | Out-Null
    Write-Host "AOSP_FREEZER_DEFAULT_RESTORED; rebooting."
    Invoke-Adb -Arguments @("reboot") | Out-Null
}

Write-Host "DEVICE=$DeviceSerial"
Write-Host "PHASE=$Phase"
Write-Host "INSTALLED_TARGETS=$($installedTargets -join ',')"
Write-Host "QUICKFROZEN_MATCHES=$quickFrozenCount"
Write-Host "FROZEN_MATCHES=$frozenCount"
Write-Host "AOSP_FREEZER_MODE=$freezerMode"
Write-Host "OUTPUT=$output"
Write-Host "NOTE=vivo QuickFrozen may remain active even when AOSP use_freezer=false"
