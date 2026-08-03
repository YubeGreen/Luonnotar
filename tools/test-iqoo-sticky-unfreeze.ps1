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
    [switch]$RestoreGlobalFreezer
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

if (-not $OutputRoot) {
    $OutputRoot = Join-Path $PSScriptRoot "test-output"
}
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$output = Join-Path $OutputRoot "iqoo-sticky-$($Mode.ToLowerInvariant())-$stamp"
New-Item -ItemType Directory -Path $output -Force | Out-Null

$help = Invoke-Adb @("shell", "am", "help")
$help.Output | Set-Content (Join-Path $output "am-help.txt") -Encoding UTF8
$stickySupported =
    $help.ExitCode -eq 0 -and
    $help.Output -match "(?s)unfreeze.*--sticky|--sticky.*unfreeze"

if ($RestoreGlobalFreezer) {
    $restore = Invoke-Adb @(
        "shell", "device_config", "delete",
        "activity_manager_native_boot", "use_freezer"
    )
    $restore.Output |
        Set-Content (Join-Path $output "global-freezer-restore.txt") -Encoding UTF8
    Write-Host "Global AOSP freezer override removed; reboot before comparison."
    exit $restore.ExitCode
}

if ($Mode -eq "StickyUnfreeze" -and -not $stickySupported) {
    Write-Host "UNSUPPORTED: am unfreeze --sticky"
    exit 4
}

if ($Mode -eq "GlobalFreezerOff") {
    $global = Invoke-Adb @(
        "shell", "device_config", "put",
        "activity_manager_native_boot", "use_freezer", "false"
    )
    $global.Output |
        Set-Content (Join-Path $output "global-freezer-off.txt") -Encoding UTF8
    if ($global.ExitCode -ne 0) {
        throw "Unable to set the AOSP freezer A/B flag."
    }
    Write-Host "AOSP freezer flag set to false; reboot is required before testing."
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

$actions | ConvertTo-Json -Depth 4 |
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
    warning = "Sticky unfreeze may not override vivo QuickFrozen, PEM, fast_freezer, or single-cleaner."
    comparison = @(
        "Baseline: original ADB configuration",
        "StickyUnfreeze: targeted process-lifetime AOSP protection",
        "GlobalFreezerOff: AOSP cached-app freezer disabled after reboot"
    )
} | ConvertTo-Json -Depth 4 |
    Set-Content (Join-Path $output "summary.json") -Encoding UTF8

Write-Host "COMPLETE: $output"
