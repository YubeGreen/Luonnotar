[CmdletBinding()]
param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$Adb = 'adb',
    [string]$OutputDirectory = (Join-Path $PWD ("luonnotar-2.0-evidence-" + (Get-Date -Format 'yyyyMMdd-HHmmss')))
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$base = @()
if ($Serial) { $base = @('-s', $Serial) }
function Save-Adb([string]$Name, [string[]]$Arguments) {
    $path = Join-Path $OutputDirectory $Name
    (& $Adb @base @Arguments 2>&1 | Out-String) | Set-Content -LiteralPath $path -Encoding UTF8
}
Save-Adb '00-device.txt' @('shell','getprop')
Save-Adb '10-engine-status.json' @('shell','sh','/data/local/tmp/luonnotar2/luonnotar-guardian-v2.sh','status')
Save-Adb '11-engine-log.txt' @('shell','sh','/data/local/tmp/luonnotar2/luonnotar-guardian-v2.sh','log','500')
Save-Adb '20-processes.txt' @('shell','ps','-A')
Save-Adb '21-activity-processes.txt' @('shell','dumpsys','activity','processes')
Save-Adb '22-deviceidle.txt' @('shell','dumpsys','deviceidle')
Save-Adb '23-appops-gms.txt' @('shell','cmd','appops','get','com.google.android.gms')
Save-Adb '24-appops-whatsapp.txt' @('shell','cmd','appops','get','com.whatsapp')
Save-Adb '30-events-logcat.txt' @('logcat','-b','events','-d','-v','threadtime')
Save-Adb '31-system-logcat.txt' @('logcat','-b','system','-d','-v','threadtime')
Get-ChildItem -LiteralPath $OutputDirectory | Get-FileHash -Algorithm SHA256 |
    ForEach-Object { "$($_.Hash) *$($_.Path | Split-Path -Leaf)" } |
    Set-Content -LiteralPath (Join-Path $OutputDirectory 'SHA256SUMS.txt') -Encoding ASCII
Write-Host "Evidence captured: $OutputDirectory"
