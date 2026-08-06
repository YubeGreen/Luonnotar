[CmdletBinding()]
param(
    [ValidateSet("Baseline", "StickyUnfreeze", "GlobalFreezerOff")]
    [string]$Mode = "Baseline",
    [ValidateRange(30, 86400)]
    [int]$DurationSeconds = 600,
    [ValidateRange(5, 300)]
    [int]$PollSeconds = 15,
    [string]$AdbPath = "adb",
    [string]$DeviceSerial = "",
    [string]$OutputRoot = "",
    [switch]$RestoreGlobalFreezer,
    [string]$RestoreStateFile = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$adb = if (Test-Path -LiteralPath $AdbPath -PathType Leaf) {
    (Resolve-Path -LiteralPath $AdbPath).Path
} else {
    (Get-Command $AdbPath -ErrorAction Stop).Source
}

function Invoke-AdbHost([string[]]$Arguments) {
    $output = & $adb @Arguments 2>&1
    [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = (($output | ForEach-Object ToString) -join "`n")
    }
}

$devices = @(
    (Invoke-AdbHost @("devices", "-l")).Output -split "`r?`n" |
        Where-Object { $_ -match "^(?<serial>\S+)\s+device(?:\s|$)" } |
        ForEach-Object { $Matches.serial }
)
if ($DeviceSerial) {
    if ($DeviceSerial -notin $devices) {
        Write-Host "NO_DEVICE"
        exit 2
    }
    $serial = $DeviceSerial
} elseif ($devices.Count -eq 1) {
    $serial = $devices[0]
} elseif ($devices.Count -eq 0) {
    Write-Host "NO_DEVICE"
    exit 2
} else {
    Write-Host "MULTIPLE_DEVICES: $($devices -join ', ')"
    exit 3
}

function Invoke-Adb([string[]]$Arguments) {
    Invoke-AdbHost (@("-s", $serial) + $Arguments)
}

function Get-AospFreezerOverride {
    $result = Invoke-Adb @(
        "shell", "device_config", "get",
        "activity_manager_native_boot", "use_freezer"
    )
    $value = $result.Output.Trim()
    if ($result.ExitCode -ne 0 -or -not $value -or $value -eq "null") {
        return $null
    }
    return $value
}

function Get-DeviceBootId {
    $result = Invoke-Adb @("shell", "cat", "/proc/sys/kernel/random/boot_id")
    $value = $result.Output.Trim()
    if ($result.ExitCode -ne 0 -or -not $value) {
        throw "Unable to read device boot_id."
    }
    return $value
}

if (-not $OutputRoot) {
    $OutputRoot = Join-Path $PSScriptRoot "test-output"
}
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$safeSerial = $serial -replace '[^A-Za-z0-9_.-]', '_'
$persistentFreezerStatePath = Join-Path $OutputRoot "global-freezer-state-$safeSerial.json"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$output = Join-Path $OutputRoot "iqoo-sticky-$($Mode.ToLowerInvariant())-$stamp"
New-Item -ItemType Directory -Path $output -Force | Out-Null

$help = Invoke-Adb @("shell", "am", "help")
$help.Output | Set-Content (Join-Path $output "am-help.txt") -Encoding UTF8
$stickySupported =
    $help.ExitCode -eq 0 -and
    $help.Output -match "(?s)unfreeze.*--sticky|--sticky.*unfreeze"

if ($RestoreGlobalFreezer) {
    $stateFile = $RestoreStateFile
    if (-not $stateFile -and (Test-Path -LiteralPath $persistentFreezerStatePath -PathType Leaf)) {
        $stateFile = $persistentFreezerStatePath
    }
    $priorValue = $null
    if ($stateFile) {
        if (-not (Test-Path -LiteralPath $stateFile -PathType Leaf)) {
            throw "Restore state file not found: $stateFile"
        }
        $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
        if ($state.device -and $state.device -ne $serial) {
            throw "Restore state belongs to $($state.device), not $serial."
        }
        if ($null -ne $state.priorOverride) {
            $priorValue = [string]$state.priorOverride
        }
    } else {
        throw "No freezer restore state found. Pass -RestoreStateFile explicitly."
    }

    if ($null -eq $priorValue -or -not $priorValue) {
        $restore = Invoke-Adb @(
            "shell", "device_config", "delete",
            "activity_manager_native_boot", "use_freezer"
        )
        $restoreAction = "delete_override"
    } else {
        $restore = Invoke-Adb @(
            "shell", "device_config", "put",
            "activity_manager_native_boot", "use_freezer", $priorValue
        )
        $restoreAction = "restore_$priorValue"
    }
    $afterRestore = Get-AospFreezerOverride
    [ordered]@{
        device = $serial
        action = $restoreAction
        exitCode = $restore.ExitCode
        output = $restore.Output
        observedOverrideAfterRestoreCommand = $afterRestore
        restoreStateFile = $stateFile
    } | ConvertTo-Json -Depth 4 |
        Set-Content (Join-Path $output "global-freezer-restore.json") -Encoding UTF8
    Write-Host "AOSP freezer override restored ($restoreAction); reboot is required."
    exit $restore.ExitCode
}

if ($Mode -eq "StickyUnfreeze" -and -not $stickySupported) {
    Write-Host "UNSUPPORTED: am unfreeze --sticky"
    exit 4
}

if ($Mode -eq "GlobalFreezerOff") {
    $currentOverride = Get-AospFreezerOverride
    $currentBootId = Get-DeviceBootId

    if ($currentOverride -ne "false") {
        $state = [ordered]@{
            schema = 2
            device = $serial
            capturedAt = [DateTimeOffset]::UtcNow.ToString("o")
            priorOverride = $currentOverride
            bootIdBeforeDisable = $currentBootId
            namespace = "activity_manager_native_boot"
            key = "use_freezer"
        }
        $state | ConvertTo-Json -Depth 4 |
            Set-Content $persistentFreezerStatePath -Encoding UTF8
        $state | ConvertTo-Json -Depth 4 |
            Set-Content (Join-Path $output "global-freezer-state-before.json") -Encoding UTF8

        $global = Invoke-Adb @(
            "shell", "device_config", "put",
            "activity_manager_native_boot", "use_freezer", "false"
        )
        $global.Output |
            Set-Content (Join-Path $output "global-freezer-off.txt") -Encoding UTF8
        if ($global.ExitCode -ne 0) {
            throw "Unable to set the AOSP freezer A/B flag."
        }
        $afterSet = Get-AospFreezerOverride
        [ordered]@{
            phase = "prepared"
            requested = "false"
            observedOverride = $afterSet
            bootIdBeforeDisable = $currentBootId
            restoreStateFile = $persistentFreezerStatePath
            nextStep = "Reboot the device, then run the same GlobalFreezerOff command again."
        } | ConvertTo-Json -Depth 4 |
            Set-Content (Join-Path $output "global-freezer-off-verification.json") -Encoding UTF8
        if ($afterSet -ne "false") {
            throw "device_config did not retain use_freezer=false."
        }
        Write-Host "PREPARED: AOSP freezer flag is false. Reboot, then rerun the same command."
        Write-Host "Restore later with: -RestoreGlobalFreezer -RestoreStateFile `"$persistentFreezerStatePath`""
        exit 0
    }

    if (-not (Test-Path -LiteralPath $persistentFreezerStatePath -PathType Leaf)) {
        throw "Global freezer is already false, but the saved pre-test state is missing: $persistentFreezerStatePath"
    }
    $savedState = Get-Content -LiteralPath $persistentFreezerStatePath -Raw | ConvertFrom-Json
    if ($savedState.device -and $savedState.device -ne $serial) {
        throw "Saved freezer state belongs to $($savedState.device), not $serial."
    }
    if ($savedState.bootIdBeforeDisable -eq $currentBootId) {
        [ordered]@{
            phase = "reboot_required"
            currentOverride = $currentOverride
            currentBootId = $currentBootId
            bootIdBeforeDisable = $savedState.bootIdBeforeDisable
        } | ConvertTo-Json -Depth 4 |
            Set-Content (Join-Path $output "global-freezer-reboot-required.json") -Encoding UTF8
        Write-Host "REBOOT_REQUIRED: the device has not rebooted since use_freezer=false was set."
        exit 5
    }
    [ordered]@{
        phase = "post_reboot_collection"
        observedOverride = $currentOverride
        currentBootId = $currentBootId
        bootIdBeforeDisable = $savedState.bootIdBeforeDisable
        restoreStateFile = $persistentFreezerStatePath
    } | ConvertTo-Json -Depth 4 |
        Set-Content (Join-Path $output "global-freezer-post-reboot.json") -Encoding UTF8
    Write-Host "POST_REBOOT: collecting the GlobalFreezerOff comparison window."
}

$targetPattern =
    "com\.google\.android\.gms|com\.whatsapp|ch\.protonvpn\.android|" +
    "com\.tailscale\.ipn|com\.yubegreen\.luonnotar"
$knownPidByProcess = @{}
$actions = [System.Collections.Generic.List[object]]::new()
$deadline = (Get-Date).AddSeconds($DurationSeconds)

do {
    $ps = Invoke-Adb @("shell", "ps", "-A")
    $lines = @($ps.Output -split "`r?`n" | Where-Object { $_ -match $targetPattern })
    foreach ($line in $lines) {
        $columns = @($line -split "\s+" | Where-Object { $_ })
        if ($columns.Count -lt 2) { continue }
        $processName = $columns[-1]
        $pid = 0
        [void][int]::TryParse($columns[1], [ref]$pid)
        if ($pid -le 0) { continue }
        $previousPid = $knownPidByProcess[$processName]
        $restarted = $null -ne $previousPid -and $previousPid -ne $pid
        if ($Mode -eq "StickyUnfreeze" -and $previousPid -ne $pid) {
            $result = Invoke-Adb @(
                "shell", "am", "unfreeze", "--sticky",
                $processName, "--user", "0"
            )
            $actions.Add([pscustomobject]@{
                epochMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                processName = $processName
                pid = $pid
                restarted = $restarted
                exitCode = $result.ExitCode
                output = $result.Output.Trim()
            })
        }
        $knownPidByProcess[$processName] = $pid
    }
    Start-Sleep -Seconds $PollSeconds
} while ((Get-Date) -lt $deadline)

$actionArray = @($actions)
ConvertTo-Json -InputObject $actionArray -Depth 4 |
    Set-Content (Join-Path $output "sticky-actions.json") -Encoding UTF8
(Invoke-Adb @("shell", "dumpsys", "activity")).Output |
    Set-Content (Join-Path $output "dumpsys-activity.txt") -Encoding UTF8
(Invoke-Adb @("logcat", "-d", "-v", "epoch")).Output |
    Set-Content (Join-Path $output "logcat-epoch.txt") -Encoding UTF8

[ordered]@{
    mode = $Mode
    device = $serial
    stickySupported = $stickySupported
    durationSeconds = $DurationSeconds
    actionCount = $actions.Count
    freezerOverride = Get-AospFreezerOverride
    bootId = Get-DeviceBootId
    restoreStateFile = if ($Mode -eq "GlobalFreezerOff") { $persistentFreezerStatePath } else { $null }
    warning = "Sticky unfreeze may not override vivo QuickFrozen, PEM, fast_freezer, or single-cleaner."
    comparison = @(
        "Baseline: original ADB configuration",
        "StickyUnfreeze: targeted process-lifetime AOSP protection",
        "GlobalFreezerOff: AOSP cached-app freezer disabled after reboot"
    )
} | ConvertTo-Json -Depth 4 |
    Set-Content (Join-Path $output "summary.json") -Encoding UTF8

Write-Host "COMPLETE: $output"
