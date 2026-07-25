[CmdletBinding()]
param(
    [string]$ApkPath = "",
    [ValidateRange(70, 900)]
    [int]$ScreenOffSeconds = 80,
    [ValidateRange(0, 900)]
    [int]$PostWakeSeconds = 315,
    [string]$OutputRoot = "",
    [string]$AdbPath = "adb",
    [string]$DeviceSerial = "",
    [switch]$SkipInstall
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $PSScriptRoot "..\Luonnotar-1.6.3-YubeGreen-release.apk"
}
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $PSScriptRoot "test-output"
}

$script:PackageName = "com.yubegreen.luonnotar"
$script:MainComponent = "$($script:PackageName)/.MainActivity"
$script:DeviceSerial = ""
$script:AdbExecutable = ""
$script:OutputDirectory = ""
$script:OriginalScreenOffTimeout = $null
$script:ScreenTimeoutChanged = $false
$script:ValidationFailures = [System.Collections.Generic.List[string]]::new()
$script:ValidationResults = [System.Collections.Generic.List[object]]::new()

function Resolve-AdbExecutable {
    param([string]$RequestedPath)

    if (Test-Path -LiteralPath $RequestedPath -PathType Leaf) {
        return (Resolve-Path -LiteralPath $RequestedPath).Path
    }
    $command = Get-Command $RequestedPath -ErrorAction Stop
    return $command.Source
}

function Invoke-AdbHost {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [switch]$AllowFailure
    )

    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $script:AdbExecutable @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    $text = (($output | ForEach-Object { $_.ToString() }) -join "`n").TrimEnd()
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb $($Arguments -join ' ') failed with exit code $exitCode`n$text"
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $text
    }
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [switch]$AllowFailure
    )

    $allArguments = @("-s", $script:DeviceSerial) + $Arguments
    return Invoke-AdbHost -Arguments $allArguments -AllowFailure:$AllowFailure
}

function Get-OnlineDevices {
    $result = Invoke-AdbHost -Arguments @("devices", "-l") -AllowFailure
    if ($result.ExitCode -ne 0) {
        return @()
    }
    $devices = [System.Collections.Generic.List[string]]::new()
    foreach ($line in ($result.Output -split "`r?`n")) {
        if ($line -match "^(?<serial>\S+)\s+device(?:\s|$)") {
            $devices.Add($Matches.serial)
        }
    }
    return @($devices)
}

function Wait-ForUniqueDevice {
    param(
        [int]$Attempts = 40,
        [int]$DelaySeconds = 5
    )

    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        $devices = @(Get-OnlineDevices)
        if ($devices.Count -eq 1) {
            return $devices[0]
        }
        if ($devices.Count -gt 1) {
            Write-Host "MULTIPLE_DEVICES: $($devices -join ', ')"
            exit 3
        }
        if ($attempt -lt $Attempts) {
            Write-Host "ADB device not visible yet ($attempt/$Attempts); waiting for the remote connection..."
            Start-Sleep -Seconds $DelaySeconds
        }
    }
    Write-Host "NO_DEVICE"
    exit 2
}

function Wait-ForSpecificDevice {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial,
        [int]$Attempts = 40,
        [int]$DelaySeconds = 5
    )

    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        $state = Invoke-AdbHost -Arguments @("-s", $Serial, "get-state") -AllowFailure
        if ($state.ExitCode -eq 0 -and $state.Output.Trim() -eq "device") {
            return $Serial
        }
        if ($attempt -lt $Attempts) {
            Write-Host "ADB device $Serial is not ready ($attempt/$Attempts); waiting..."
            Start-Sleep -Seconds $DelaySeconds
        }
    }
    Write-Host "NO_DEVICE: $Serial"
    exit 2
}

function Save-AdbOutput {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [Parameter(Mandatory = $true)]
        [string]$FileName
    )

    $path = Join-Path $script:OutputDirectory $FileName
    try {
        $result = Invoke-Adb -Arguments $Arguments -AllowFailure
        @(
            "command: adb -s $($script:DeviceSerial) $($Arguments -join ' ')"
            "exitCode: $($result.ExitCode)"
            ""
            $result.Output
        ) | Set-Content -LiteralPath $path -Encoding UTF8
    } catch {
        @(
            "command: adb -s $($script:DeviceSerial) $($Arguments -join ' ')"
            "collectionError: $($_.Exception.Message)"
        ) | Set-Content -LiteralPath $path -Encoding UTF8
    }
}

function Save-StateSnapshot {
    param([string]$Prefix)

    Save-AdbOutput -Arguments @("shell", "dumpsys", "battery") -FileName "$Prefix-battery.txt"
    Save-AdbOutput -Arguments @("shell", "dumpsys", "deviceidle") -FileName "$Prefix-deviceidle.txt"
    Save-AdbOutput -Arguments @("shell", "dumpsys", "power") -FileName "$Prefix-power.txt"
    Save-AdbOutput -Arguments @("shell", "dumpsys", "connectivity") -FileName "$Prefix-connectivity.txt"
    Save-AdbOutput -Arguments @("shell", "dumpsys", "wifi") -FileName "$Prefix-wifi.txt"
    Save-AdbOutput -Arguments @("shell", "dumpsys", "package", $script:PackageName) -FileName "$Prefix-package.txt"
}

function Set-ScreenAwake {
    Invoke-Adb -Arguments @("shell", "input", "keyevent", "224") -AllowFailure | Out-Null
    Invoke-Adb -Arguments @("shell", "wm", "dismiss-keyguard") -AllowFailure | Out-Null
}

function Move-UiToTop {
    1..4 | ForEach-Object {
        Invoke-Adb -Arguments @(
            "shell", "input", "swipe", "630", "650", "630", "2300", "350"
        ) -AllowFailure | Out-Null
    }
}

function Extend-ScreenTimeoutForTest {
    $current = Invoke-Adb -Arguments @(
        "shell", "settings", "get", "system", "screen_off_timeout"
    ) -AllowFailure
    $trimmed = $current.Output.Trim()
    if ($current.ExitCode -ne 0 -or $trimmed -notmatch "^\d+$") {
        throw "Unable to read screen_off_timeout; refusing an unreliable screen-on cadence test."
    }
    $script:OriginalScreenOffTimeout = [long]$trimmed
    if ($script:OriginalScreenOffTimeout -ge 600000) {
        return
    }
    $updated = Invoke-Adb -Arguments @(
        "shell", "settings", "put", "system", "screen_off_timeout", "600000"
    ) -AllowFailure
    if ($updated.ExitCode -ne 0) {
        throw "Unable to extend screen_off_timeout for the cadence test."
    }
    $script:ScreenTimeoutChanged = $true
}

function Restore-ScreenTimeout {
    if (
        -not $script:ScreenTimeoutChanged -or
        $null -eq $script:OriginalScreenOffTimeout
    ) {
        return
    }
    $restored = Invoke-Adb -Arguments @(
        "shell", "settings", "put", "system", "screen_off_timeout",
        $script:OriginalScreenOffTimeout.ToString()
    ) -AllowFailure
    if ($restored.ExitCode -ne 0) {
        throw "Unable to restore screen_off_timeout."
    }
    $script:ScreenTimeoutChanged = $false
}

function Wait-WithProgress {
    param(
        [int]$Seconds,
        [switch]$KeepAwake
    )

    $remaining = $Seconds
    $elapsed = 0
    while ($remaining -gt 0) {
        $slice = [Math]::Min(10, $remaining)
        Start-Sleep -Seconds $slice
        $remaining -= $slice
        $elapsed += $slice
        if ($KeepAwake -and (($elapsed % 20) -eq 0 -or $remaining -eq 0)) {
            Set-ScreenAwake
        }
        Write-Host "  remaining: $remaining s"
    }
}

function Get-UiXml {
    $remotePath = "/sdcard/luonnotar-iqoo-test-ui.xml"
    Invoke-Adb -Arguments @("shell", "uiautomator", "dump", $remotePath) -AllowFailure | Out-Null
    $result = Invoke-Adb -Arguments @("shell", "cat", $remotePath) -AllowFailure
    Invoke-Adb -Arguments @("shell", "rm", "-f", $remotePath) -AllowFailure | Out-Null
    $xmlStart = $result.Output.IndexOf("<?xml", [StringComparison]::Ordinal)
    if ($xmlStart -lt 0) {
        return $null
    }
    try {
        return [xml]$result.Output.Substring($xmlStart)
    } catch {
        return $null
    }
}

function Find-UiNode {
    param(
        [Parameter(Mandatory = $true)]
        [xml]$UiXml,
        [Parameter(Mandatory = $true)]
        [string[]]$Texts
    )

    foreach ($node in $UiXml.SelectNodes("//node")) {
        $text = [string]$node.GetAttribute("text")
        $description = [string]$node.GetAttribute("content-desc")
        foreach ($candidate in $Texts) {
            if ($text -eq $candidate -or $description.StartsWith($candidate, [StringComparison]::Ordinal)) {
                return $node
            }
        }
    }
    return $null
}

function Tap-UiNode {
    param([Parameter(Mandatory = $true)]$Node)

    $bounds = [string]$Node.GetAttribute("bounds")
    if ($bounds -notmatch "^\[(?<left>\d+),(?<top>\d+)\]\[(?<right>\d+),(?<bottom>\d+)\]$") {
        return $false
    }
    $x = ([int]$Matches.left + [int]$Matches.right) / 2
    $y = ([int]$Matches.top + [int]$Matches.bottom) / 2
    $result = Invoke-Adb -Arguments @("shell", "input", "tap", "$([int]$x)", "$([int]$y)") -AllowFailure
    return $result.ExitCode -eq 0
}

function Ensure-UiSetting {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$EnabledTexts,
        [Parameter(Mandatory = $true)]
        [string[]]$EnableTexts,
        [Parameter(Mandatory = $true)]
        [string]$SettingName
    )

    for ($attempt = 0; $attempt -lt 7; $attempt++) {
        $ui = Get-UiXml
        if ($null -ne $ui) {
            if ($null -ne (Find-UiNode -UiXml $ui -Texts $EnabledTexts)) {
                Write-Host "$SettingName already enabled."
                return $true
            }
            $enableNode = Find-UiNode -UiXml $ui -Texts $EnableTexts
            if ($null -ne $enableNode) {
                if (-not (Tap-UiNode -Node $enableNode)) {
                    return $false
                }
                Start-Sleep -Seconds 2
                $confirmation = Get-UiXml
                if ($null -ne $confirmation -and
                    $null -ne (Find-UiNode -UiXml $confirmation -Texts $EnabledTexts)) {
                    Write-Host "$SettingName enabled."
                    return $true
                }
            }
        }
        Invoke-Adb -Arguments @(
            "shell", "input", "swipe", "540", "1700", "540", "650", "500"
        ) -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 700
    }
    Write-Host "$SettingName could not be confirmed from the app UI."
    return $false
}

function ConvertFrom-TimelineLog {
    param([string[]]$Lines)

    $entries = [System.Collections.Generic.List[object]]::new()
    foreach ($line in $Lines) {
        if ($line -notmatch "guardian_timeline") {
            continue
        }
        if ($line -notmatch "^\s*(?<epoch>\d+\.\d+)") {
            continue
        }
        $epoch = 0.0
        if (-not [double]::TryParse(
            $Matches.epoch,
            [System.Globalization.NumberStyles]::Float,
            [System.Globalization.CultureInfo]::InvariantCulture,
            [ref]$epoch
        )) {
            continue
        }
        if ($line -notmatch "timelineEvent=(?<event>[^,}]+)") {
            continue
        }
        $entries.Add([pscustomobject]@{
            Epoch = $epoch
            Event = $Matches.event.Trim()
            Line = $line
        })
    }
    return @($entries | Sort-Object Epoch)
}

function Add-ValidationResult {
    param(
        [string]$Name,
        [bool]$Passed,
        [string]$Details
    )

    $script:ValidationResults.Add([pscustomobject]@{
        name = $Name
        passed = $Passed
        details = $Details
    })
    if (-not $Passed) {
        $script:ValidationFailures.Add("$Name`: $Details")
        Write-Host "[FAIL] $Name - $Details"
    } else {
        Write-Host "[PASS] $Name - $Details"
    }
}

function Test-ProbeWindows {
    param(
        [object[]]$Entries,
        [double]$AnchorEpoch,
        [double]$EndEpoch
    )

    $starts = @($Entries | Where-Object {
        $_.Event -eq "https_probe_started" -and
        $_.Epoch -ge $AnchorEpoch -and
        $_.Epoch -le $EndEpoch
    })
    $windows = @(
        [pscustomobject]@{ Name = "screen-off probe near 0s"; Min = 0.0; Max = 15.0 },
        [pscustomobject]@{ Name = "screen-off probe near 30s"; Min = 20.0; Max = 45.0 },
        [pscustomobject]@{ Name = "screen-off probe near 60s"; Min = 50.0; Max = 80.0 }
    )
    foreach ($window in $windows) {
        $matches = @($starts | Where-Object {
            $offset = $_.Epoch - $AnchorEpoch
            $offset -ge $window.Min -and $offset -le $window.Max
        })
        $offsets = @($matches | ForEach-Object {
            [Math]::Round($_.Epoch - $AnchorEpoch, 2)
        })
        Add-ValidationResult -Name $window.Name -Passed ($matches.Count -gt 0) `
            -Details "observed offsets: $($offsets -join ', ') s"
    }
}

function Test-VpnOnlyEvidence {
    param([object[]]$Entries)

    $starts = @($Entries | Where-Object { $_.Event -eq "https_probe_started" })
    $invalid = @($starts | Where-Object {
        $_.Line -notmatch "capturedNetworkHandle=(?<handle>\d+)" -or
        [long]$Matches.handle -le 0
    })
    $fallbackLines = @($Entries | Where-Object {
        $_.Line -match "(?i)(direct[_ -]?fallback|fallback[_ -]?direct|non[_ -]?vpn|url\.openconnection)"
    })
    $passed = $starts.Count -gt 0 -and $invalid.Count -eq 0 -and $fallbackLines.Count -eq 0
    Add-ValidationResult -Name "VPN-only probe evidence" -Passed $passed `
        -Details "starts=$($starts.Count), missingHandle=$($invalid.Count), directFallbackMarkers=$($fallbackLines.Count)"
}

function Test-MaximumProbeConcurrency {
    param([object[]]$Entries)

    $terminalEvents = @(
        "https_probe_succeeded",
        "https_probe_failed",
        "https_probe_timeout",
        "https_probe_result_discarded"
    )
    $inFlight = 0
    $maximum = 0
    foreach ($entry in $Entries) {
        if ($entry.Event -eq "https_probe_started") {
            $inFlight++
            $maximum = [Math]::Max($maximum, $inFlight)
        } elseif ($terminalEvents -contains $entry.Event -and $inFlight -gt 0) {
            $inFlight--
        }
    }
    Add-ValidationResult -Name "maximum one probe in flight" -Passed ($maximum -le 1) `
        -Details "maximum observed concurrent starts=$maximum; unfinished=$inFlight"
}

function Test-PostWakeCadence {
    param(
        [object[]]$Entries,
        [double]$ScreenOnEpoch,
        [int]$ObservedSeconds
    )

    $starts = @($Entries | Where-Object {
        $_.Event -eq "https_probe_started" -and $_.Epoch -ge $ScreenOnEpoch
    })
    $immediate = @($starts | Where-Object {
        $offset = $_.Epoch - $ScreenOnEpoch
        $offset -ge 0 -and $offset -le 15
    })
    Add-ValidationResult -Name "screen-on forced probe" -Passed ($immediate.Count -gt 0) `
        -Details "immediate starts=$($immediate.Count)"

    $tooEarly = @($starts | Where-Object {
        $offset = $_.Epoch - $ScreenOnEpoch
        $offset -ge 20 -and $offset -lt 270
    })
    Add-ValidationResult -Name "no 30-second cadence while screen is on" `
        -Passed ($tooEarly.Count -eq 0) `
        -Details "unexpected starts between 20s and 270s=$($tooEarly.Count)"

    if ($ObservedSeconds -lt 285) {
        Add-ValidationResult -Name "five-minute screen-on cadence" -Passed $false `
            -Details "SKIPPED: PostWakeSeconds=$ObservedSeconds; use at least 305 seconds for full verification"
        return
    }
    $fiveMinute = @($starts | Where-Object {
        $offset = $_.Epoch - $ScreenOnEpoch
        $offset -ge 275 -and $offset -le 335
    })
    $offsets = @($fiveMinute | ForEach-Object {
        [Math]::Round($_.Epoch - $ScreenOnEpoch, 2)
    })
    Add-ValidationResult -Name "five-minute screen-on cadence" -Passed ($fiveMinute.Count -gt 0) `
        -Details "observed offsets: $($offsets -join ', ') s"
}

$script:AdbExecutable = Resolve-AdbExecutable -RequestedPath $AdbPath
$script:DeviceSerial = if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
    Wait-ForUniqueDevice
} else {
    Wait-ForSpecificDevice -Serial $DeviceSerial.Trim()
}
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop).Path
$resolvedOutputRoot = [System.IO.Path]::GetFullPath($OutputRoot)
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:OutputDirectory = Join-Path $resolvedOutputRoot "iqoo-aggressive-$timestamp"
New-Item -ItemType Directory -Path $script:OutputDirectory -Force | Out-Null

$runFailure = $null
$screenWasTurnedOff = $false
try {
    Write-Host "Device: $($script:DeviceSerial)"
    Write-Host "Output: $($script:OutputDirectory)"
    if ($SkipInstall) {
        Write-Host "Install skipped by request; validating the existing package."
        $installedVersion = Invoke-Adb -Arguments @(
            "shell", "dumpsys", "package", $script:PackageName
        ) -AllowFailure
        $installedVersion.Output | Set-Content -LiteralPath (
            Join-Path $script:OutputDirectory "install.txt"
        ) -Encoding UTF8
        if (
            $installedVersion.ExitCode -ne 0 -or
            $installedVersion.Output -notmatch "versionCode=20(?:\s|$)" -or
            $installedVersion.Output -notmatch "versionName=1\.6\.3(?:\s|$)"
        ) {
            throw "SkipInstall requires Luonnotar 1.6.3 (versionCode 20) to be installed."
        }
    } else {
        Write-Host "Installing: $resolvedApk"
        $install = Invoke-Adb -Arguments @("install", "-r", $resolvedApk) -AllowFailure
        $install.Output | Set-Content -LiteralPath (
            Join-Path $script:OutputDirectory "install.txt"
        ) -Encoding UTF8
        if ($install.ExitCode -ne 0 -or $install.Output -notmatch "(?m)^Success\s*$") {
            throw "APK install/update failed. Existing app data was not removed.`n$($install.Output)"
        }
    }

    Set-ScreenAwake
    $launch = Invoke-Adb -Arguments @("shell", "am", "start", "-W", "-n", $script:MainComponent) -AllowFailure
    $launch.Output | Set-Content -LiteralPath (Join-Path $script:OutputDirectory "launch.txt") -Encoding UTF8
    if ($launch.ExitCode -ne 0 -or $launch.Output -match "(?i)(error|exception)") {
        throw "Unable to start Luonnotar.`n$($launch.Output)"
    }
    Start-Sleep -Seconds 3

    Move-UiToTop
    $guardianEnabled = Ensure-UiSetting `
        -EnabledTexts @("停止极限保活") `
        -EnableTexts @("开启极限保活", "继续极限保活") `
        -SettingName "Extreme guardian"
    if (-not $guardianEnabled) {
        throw "Extreme guardian is not enabled. Accept the first-run policy and enable it once, then rerun."
    }
    $aggressiveEnabled = Ensure-UiSetting `
        -EnabledTexts @("关闭 vivo/iQOO 激进保活") `
        -EnableTexts @(
            "开启 vivo/iQOO 激进保活（建议）",
            "开启激进保活（仅 vivo/iQOO 建议）"
        ) `
        -SettingName "vivo/iQOO aggressive mode"
    if (-not $aggressiveEnabled) {
        throw "vivo/iQOO aggressive mode could not be enabled or confirmed."
    }

    Extend-ScreenTimeoutForTest
    Set-ScreenAwake
    Save-StateSnapshot -Prefix "pre"
    Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
    Invoke-Adb -Arguments @(
        "shell", "log", "-t", "LuonnotarTest",
        "IQOO_AGGRESSIVE_BEGIN_$timestamp"
    ) -AllowFailure | Out-Null

    Write-Host "Turning the screen off for $ScreenOffSeconds seconds..."
    Invoke-Adb -Arguments @("shell", "input", "keyevent", "26") | Out-Null
    $screenWasTurnedOff = $true
    Wait-WithProgress -Seconds $ScreenOffSeconds

    $offLog = Invoke-Adb -Arguments @(
        "logcat", "-v", "epoch", "-d", "Luonnotar:I", "LuonnotarTest:I", "*:S"
    ) -AllowFailure
    $offLog.Output | Set-Content `
        -LiteralPath (Join-Path $script:OutputDirectory "screen-off-logcat.txt") `
        -Encoding UTF8
    $offLines = @($offLog.Output -split "`r?`n")
    $offEntries = @(ConvertFrom-TimelineLog -Lines $offLines)
    @($offLines | Where-Object { $_ -match "guardian_timeline" }) |
        Set-Content -LiteralPath (Join-Path $script:OutputDirectory "screen-off-timeline.txt") -Encoding UTF8

    $screenOffEvent = $offEntries |
        Where-Object { $_.Event -eq "screen_off" } |
        Select-Object -First 1
    Add-ValidationResult -Name "screen-off receiver event" `
        -Passed ($null -ne $screenOffEvent) `
        -Details $(if ($null -eq $screenOffEvent) { "not observed" } else { "epoch=$($screenOffEvent.Epoch)" })
    if ($null -ne $screenOffEvent) {
        Test-ProbeWindows -Entries $offEntries `
            -AnchorEpoch $screenOffEvent.Epoch `
            -EndEpoch ($screenOffEvent.Epoch + $ScreenOffSeconds + 15)
    }
    Test-VpnOnlyEvidence -Entries $offEntries
    Test-MaximumProbeConcurrency -Entries $offEntries
    Save-StateSnapshot -Prefix "screen-off"

    Write-Host "Waking the device and observing the normal five-minute cadence..."
    Set-ScreenAwake
    $screenWasTurnedOff = $false
    Wait-WithProgress -Seconds $PostWakeSeconds -KeepAwake

    $fullLog = Invoke-Adb -Arguments @(
        "logcat", "-v", "epoch", "-d", "Luonnotar:I", "LuonnotarTest:I", "*:S"
    ) -AllowFailure
    $fullLog.Output | Set-Content `
        -LiteralPath (Join-Path $script:OutputDirectory "full-logcat.txt") `
        -Encoding UTF8
    $fullLines = @($fullLog.Output -split "`r?`n")
    $fullEntries = @(ConvertFrom-TimelineLog -Lines $fullLines)
    @($fullLines | Where-Object { $_ -match "guardian_timeline" }) |
        Set-Content -LiteralPath (Join-Path $script:OutputDirectory "timeline.txt") -Encoding UTF8
    $screenOnEvent = $fullEntries |
        Where-Object { $_.Event -eq "screen_on" } |
        Select-Object -Last 1
    Add-ValidationResult -Name "screen-on receiver event" `
        -Passed ($null -ne $screenOnEvent) `
        -Details $(if ($null -eq $screenOnEvent) { "not observed" } else { "epoch=$($screenOnEvent.Epoch)" })
    if ($null -ne $screenOnEvent) {
        Test-PostWakeCadence -Entries $fullEntries `
            -ScreenOnEpoch $screenOnEvent.Epoch `
            -ObservedSeconds $PostWakeSeconds
    }
    Test-MaximumProbeConcurrency -Entries $fullEntries
    Save-StateSnapshot -Prefix "post"
} catch {
    $runFailure = $_
    Write-Host "TEST_ERROR: $($_.Exception.Message)"
} finally {
    if ($script:DeviceSerial) {
        try {
            Set-ScreenAwake
            $screenWasTurnedOff = $false
            Restore-ScreenTimeout
        } catch {
            Write-Host "WAKE_RECOVERY_FAILED: $($_.Exception.Message)"
        }
        if ($script:OutputDirectory -and (Test-Path -LiteralPath $script:OutputDirectory)) {
            try {
                Save-StateSnapshot -Prefix "final"
                Save-AdbOutput -Arguments @(
                    "logcat", "-v", "epoch", "-d"
                ) -FileName "final-logcat-all.txt"
                Save-AdbOutput -Arguments @(
                    "shell", "dumpsys", "alarm", $script:PackageName
                ) -FileName "final-alarm.txt"
            } catch {
                Write-Host "FINAL_EXPORT_WARNING: $($_.Exception.Message)"
            }
        }
    }
}

$summary = [ordered]@{
    timestamp = $timestamp
    device = $script:DeviceSerial
    apk = $resolvedApk
    screenOffSeconds = $ScreenOffSeconds
    postWakeSeconds = $PostWakeSeconds
    outputDirectory = $script:OutputDirectory
    runError = if ($null -eq $runFailure) { $null } else { $runFailure.Exception.Message }
    validationFailures = @($script:ValidationFailures)
    validations = @($script:ValidationResults)
}
$summary | ConvertTo-Json -Depth 6 |
    Set-Content -LiteralPath (Join-Path $script:OutputDirectory "validation-summary.json") -Encoding UTF8

if ($null -ne $runFailure -or $script:ValidationFailures.Count -gt 0) {
    Write-Host "FAILED: artifacts are available at $($script:OutputDirectory)"
    exit 1
}

Write-Host "PASSED: artifacts are available at $($script:OutputDirectory)"
exit 0
