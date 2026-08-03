[CmdletBinding()]
param(
    [ValidateSet('Install','Start','Stop','Restart','Status','Cycle','Log','Uninstall')]
    [string]$Action = 'Install',
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$Adb = 'adb',
    [ValidateRange(5,300)]
    [int]$PollSeconds = 15,
    [ValidateRange(15,1800)]
    [int]$ReassertSeconds = 60
)

$ErrorActionPreference = 'Stop'
$RemoteRoot = '/data/local/tmp/luonnotar2'
$RemoteScript = "$RemoteRoot/luonnotar-guardian-v2.sh"
$LocalScript = Join-Path $PSScriptRoot 'device/luonnotar-guardian-v2.sh'

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
    $base = @()
    if ($Serial) { $base += @('-s', $Serial) }
    & $Adb @base @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb exited with $LASTEXITCODE: $($Arguments -join ' ')"
    }
}

function Invoke-Guardian {
    param([string]$Command)
    Invoke-Adb shell env `
        "LUONNOTAR_POLL_SECONDS=$PollSeconds" `
        "LUONNOTAR_REASSERT_SECONDS=$ReassertSeconds" `
        sh $RemoteScript $Command
}

if (-not (Get-Command $Adb -ErrorAction SilentlyContinue)) {
    throw "adb not found: $Adb"
}
if ($Serial) { Invoke-Adb get-state | Out-Null } else { Invoke-Adb get-state | Out-Null }

switch ($Action) {
    'Install' {
        if (-not (Test-Path -LiteralPath $LocalScript)) { throw "Missing $LocalScript" }
        Invoke-Adb shell mkdir -p $RemoteRoot
        Invoke-Adb push $LocalScript $RemoteScript
        Invoke-Adb shell chmod 755 $RemoteScript
        Invoke-Guardian 'restart'
        Write-Host "Installed external shell guardian at $RemoteScript"
        Invoke-Guardian 'status'
    }
    'Start' { Invoke-Guardian 'start'; Invoke-Guardian 'status' }
    'Stop' { Invoke-Guardian 'stop' }
    'Restart' { Invoke-Guardian 'restart'; Invoke-Guardian 'status' }
    'Status' { Invoke-Guardian 'status' }
    'Cycle' { Invoke-Guardian 'cycle' }
    'Log' { Invoke-Adb shell sh $RemoteScript log 200 }
    'Uninstall' {
        try { Invoke-Guardian 'stop' } catch { Write-Warning $_ }
        Invoke-Adb shell rm -rf $RemoteRoot
        Write-Host 'Removed Luonnotar external shell guardian.'
    }
}
