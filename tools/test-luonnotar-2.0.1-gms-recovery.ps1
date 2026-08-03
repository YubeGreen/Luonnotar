[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Device,
    [int]$WatchSeconds = 70,
    [string]$OutputDirectory = "$env:USERPROFILE\Desktop"
)
$ErrorActionPreference = 'Stop'
$Package = 'com.yubegreen.luonnotar'
$ButtonText = '立即重启 GMS 并验证 PID'
$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$Out = Join-Path $OutputDirectory "luonnotar-2.0.1-gms-$Stamp"
New-Item -ItemType Directory -Path $Out -Force | Out-Null

function Invoke-Adb([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments) {
    & adb -s $Device @Arguments
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments -join ' ')" }
}

function Get-GmsPids {
    $Rows = & adb -s $Device shell ps -A -o PID,NAME,ARGS
    @($Rows | Select-String 'com\.google\.android\.gms(?:\.persistent|:|\s|$)')
}

$Version = & adb -s $Device shell dumpsys package $Package |
    Select-String 'versionCode=|versionName='
$Version | Set-Content (Join-Path $Out 'version.txt') -Encoding UTF8
if (-not ($Version -match 'versionCode=46')) {
    throw 'Device is not running Luonnotar versionCode 46 (2.0.1).'
}

Invoke-Adb shell am force-stop $Package
Invoke-Adb shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 2

$Found = $null
for ($Attempt = 0; $Attempt -lt 7 -and -not $Found; $Attempt++) {
    Invoke-Adb shell uiautomator dump /sdcard/luonnotar-2.0.1-ui.xml | Out-Null
    $XmlText = (& adb -s $Device exec-out cat /sdcard/luonnotar-2.0.1-ui.xml) -join "`n"
    [xml]$Xml = $XmlText
    $Found = $Xml.SelectSingleNode("//*[@text='$ButtonText']")
    if (-not $Found) {
        Invoke-Adb shell input swipe 500 1700 500 650 350
        Start-Sleep -Milliseconds 700
    }
}
if (-not $Found) { throw "Button not found: $ButtonText" }
if ($Found.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    throw "Invalid bounds: $($Found.bounds)"
}
$X1 = [int]$Matches[1]
$Y1 = [int]$Matches[2]
$X2 = [int]$Matches[3]
$Y2 = [int]$Matches[4]
$X = [int](($X1 + $X2) / 2)
$Y = [int](($Y1 + $Y2) / 2)

Get-GmsPids | Set-Content (Join-Path $Out 'pids-before.txt') -Encoding UTF8
Invoke-Adb logcat -c
Invoke-Adb shell input tap $X $Y

$Timeline = Join-Path $Out 'pid-timeline.txt'
for ($Second = 0; $Second -le $WatchSeconds; $Second++) {
    "===== +${Second}s =====" | Add-Content $Timeline -Encoding UTF8
    Get-GmsPids | Add-Content $Timeline -Encoding UTF8
    Start-Sleep -Seconds 1
}

& adb -s $Device logcat -d -v threadtime |
    Select-String 'gms_recovery_|force-stop|package died|Start proc|am_app_frozen|com.google.android.gms|PrivilegedGuardian' |
    Set-Content (Join-Path $Out 'filtered-logcat.txt') -Encoding UTF8
Invoke-Adb shell uiautomator dump /sdcard/luonnotar-2.0.1-ui-after.xml | Out-Null
& adb -s $Device exec-out cat /sdcard/luonnotar-2.0.1-ui-after.xml |
    Set-Content (Join-Path $Out 'ui-after.xml') -Encoding UTF8

Write-Host "Evidence saved: $Out"
