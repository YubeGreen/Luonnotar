[CmdletBinding()]
param(
    [string]$DeviceSerial = "127.0.0.1:5132",
    [ValidateSet("STANDARD", "IQOO_COOPERATIVE", "ADB_PASSIVE", "LAB_EXTREME")]
    [string]$Profile = "",
    [Nullable[int]]$LabLevel = $null,
    [Nullable[bool]]$ScreenOffCpuGuard = $null,
    [Nullable[bool]]$HighPerfWifiLock = $null,
    [Nullable[bool]]$PeriodicDns = $null,
    [Nullable[bool]]$PeriodicHttps = $null,
    [Nullable[bool]]$AutomaticMtalk = $null,
    [Nullable[bool]]$PersistentNetworkLease = $null,
    [Alias("PersistentMtalkSocket")]
    [Nullable[bool]]$PersistentHeartbeatSocket = $null,
    [Nullable[bool]]$FrequentNotificationRefresh = $null,
    [Nullable[bool]]$MonitorGms = $null,
    [Nullable[bool]]$MonitorWhatsApp = $null,
    [Nullable[bool]]$MonitorWhatsAppBusiness = $null,
    [switch]$OriginOsPreventionPreset,
    [switch]$DisableOriginOsPreventionPreset,
    [switch]$OriginOsPersistentLeasePreset,
    [switch]$DisableOriginOsPersistentLeasePreset,
    [switch]$OriginOsHeartbeatSocketPreset,
    [switch]$DisableOriginOsHeartbeatSocketPreset,
    [ValidateSet("", "DNS", "HTTPS", "MTALK", "ALL")]
    [string]$ProbeNow = "",
    [switch]$StatusOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$providerUri = "content://com.yubegreen.luonnotar.adb_runtime_config"
$statusMethod = "status"
$setMethod = "set"
$probeMethod = "probe"

$presetCount = @(
    $OriginOsPreventionPreset,
    $DisableOriginOsPreventionPreset,
    $OriginOsPersistentLeasePreset,
    $DisableOriginOsPersistentLeasePreset,
    $OriginOsHeartbeatSocketPreset,
    $DisableOriginOsHeartbeatSocketPreset
).Where({ $_.IsPresent }).Count
if ($presetCount -gt 1) {
    throw "一次只能使用一个 OriginOS 预设开关"
}
if (
    -not [string]::IsNullOrWhiteSpace($ProbeNow) -and
    (
        $StatusOnly -or
        $OriginOsPreventionPreset -or
        $DisableOriginOsPreventionPreset -or
        $OriginOsPersistentLeasePreset -or
        $DisableOriginOsPersistentLeasePreset -or
        $OriginOsHeartbeatSocketPreset -or
        $DisableOriginOsHeartbeatSocketPreset -or
        -not [string]::IsNullOrWhiteSpace($Profile) -or
        $null -ne $LabLevel -or
        $null -ne $ScreenOffCpuGuard -or
        $null -ne $HighPerfWifiLock -or
        $null -ne $PeriodicDns -or
        $null -ne $PeriodicHttps -or
        $null -ne $AutomaticMtalk -or
        $null -ne $PersistentNetworkLease -or
        $null -ne $PersistentHeartbeatSocket -or
        $null -ne $FrequentNotificationRefresh -or
        $null -ne $MonitorGms -or
        $null -ne $MonitorWhatsApp -or
        $null -ne $MonitorWhatsAppBusiness
    )
) {
    throw "ProbeNow 不能与状态或配置参数同时使用"
}
if ($StatusOnly -and (
    $OriginOsPreventionPreset -or
    $DisableOriginOsPreventionPreset -or
    $OriginOsPersistentLeasePreset -or
    $DisableOriginOsPersistentLeasePreset -or
    $OriginOsHeartbeatSocketPreset -or
    $DisableOriginOsHeartbeatSocketPreset -or
    -not [string]::IsNullOrWhiteSpace($Profile) -or
    $null -ne $LabLevel -or
    $null -ne $ScreenOffCpuGuard -or
    $null -ne $HighPerfWifiLock -or
    $null -ne $PeriodicDns -or
    $null -ne $PeriodicHttps -or
    $null -ne $AutomaticMtalk -or
    $null -ne $PersistentNetworkLease -or
    $null -ne $PersistentHeartbeatSocket -or
    $null -ne $FrequentNotificationRefresh -or
    $null -ne $MonitorGms -or
    $null -ne $MonitorWhatsApp -or
    $null -ne $MonitorWhatsAppBusiness
)) {
    throw "StatusOnly 不能与配置参数同时使用"
}

if ($OriginOsPreventionPreset) {
    $HighPerfWifiLock = $true
    $PeriodicDns = $true
    $PeriodicHttps = $true
}
elseif ($DisableOriginOsPreventionPreset) {
    $HighPerfWifiLock = $false
    $PeriodicDns = $false
    $PeriodicHttps = $false
}
if ($OriginOsPersistentLeasePreset) {
    $ScreenOffCpuGuard = $true
    $HighPerfWifiLock = $true
    $PersistentNetworkLease = $true
    $PersistentHeartbeatSocket = $false
    $PeriodicDns = $false
    $PeriodicHttps = $false
    $AutomaticMtalk = $false
}
elseif ($DisableOriginOsPersistentLeasePreset) {
    $PersistentNetworkLease = $false
}
elseif ($OriginOsHeartbeatSocketPreset) {
    $ScreenOffCpuGuard = $true
    $HighPerfWifiLock = $true
    $PersistentNetworkLease = $false
    $PersistentHeartbeatSocket = $true
    $PeriodicDns = $false
    $PeriodicHttps = $false
    $AutomaticMtalk = $false
}
elseif ($DisableOriginOsHeartbeatSocketPreset) {
    $PersistentHeartbeatSocket = $false
}

function Add-BoolExtra {
    param(
        [System.Collections.Generic.List[string]]$Arguments,
        [string]$Key,
        [Nullable[bool]]$Value
    )
    if ($null -ne $Value) {
        $lower = ([bool]$Value).ToString().ToLowerInvariant()
        $Arguments.Add("--extra")
        $Arguments.Add(("{0}:b:{1}" -f $Key, $lower))
    }
}

function Parse-ProviderResult {
    param([string]$Text)

    $match = [regex]::Match(
        $Text,
        'wire=(?:"((?:\\.|[^"\\])*)"|([^}\]]*))'
    )
    if (-not $match.Success) {
        throw "努昂诺塔没有返回 ContentProvider 状态：$Text"
    }

    $wire = if ($match.Groups[1].Success) {
        $match.Groups[1].Value.Replace('\"', '"').Replace('\\', '\')
    }
    else {
        $match.Groups[2].Value.Trim()
    }

    $values = [ordered]@{}
    foreach ($pair in $wire.Split(';')) {
        $index = $pair.IndexOf('=')
        if ($index -le 0) { continue }
        $values[$pair.Substring(0, $index)] =
            $pair.Substring($index + 1)
    }
    return $values
}

$method = $statusMethod
$extraArguments = [System.Collections.Generic.List[string]]::new()

$mutationRequested =
    $OriginOsPreventionPreset -or
    $DisableOriginOsPreventionPreset -or
    $OriginOsPersistentLeasePreset -or
    $DisableOriginOsPersistentLeasePreset -or
    $OriginOsHeartbeatSocketPreset -or
    $DisableOriginOsHeartbeatSocketPreset -or
    -not [string]::IsNullOrWhiteSpace($Profile) -or
    $null -ne $LabLevel -or
    $null -ne $ScreenOffCpuGuard -or
    $null -ne $HighPerfWifiLock -or
    $null -ne $PeriodicDns -or
    $null -ne $PeriodicHttps -or
    $null -ne $AutomaticMtalk -or
    $null -ne $PersistentNetworkLease -or
    $null -ne $PersistentHeartbeatSocket -or
    $null -ne $FrequentNotificationRefresh -or
    $null -ne $MonitorGms -or
    $null -ne $MonitorWhatsApp -or
    $null -ne $MonitorWhatsAppBusiness

if (-not [string]::IsNullOrWhiteSpace($ProbeNow)) {
    $method = $probeMethod
    $extraArguments.Add("--extra")
    $extraArguments.Add(("probe:s:{0}" -f $ProbeNow))
}
elseif ($mutationRequested -and -not $StatusOnly) {
    $method = $setMethod
    if (-not [string]::IsNullOrWhiteSpace($Profile)) {
        $extraArguments.Add("--extra")
        $extraArguments.Add(("profile:s:{0}" -f $Profile))
    }
    if ($null -ne $LabLevel) {
        $level = [int]$LabLevel
        if ($level -lt 0 -or $level -gt 4) {
            throw "LabLevel 必须在 0 到 4 之间"
        }
        $extraArguments.Add("--extra")
        $extraArguments.Add(("lab_level:i:{0}" -f $level))
    }
    Add-BoolExtra $extraArguments "screen_off_cpu_guard" $ScreenOffCpuGuard
    Add-BoolExtra $extraArguments "high_perf_wifi_lock" $HighPerfWifiLock
    Add-BoolExtra $extraArguments "periodic_dns" $PeriodicDns
    Add-BoolExtra $extraArguments "periodic_https" $PeriodicHttps
    Add-BoolExtra $extraArguments "automatic_mtalk" $AutomaticMtalk
    Add-BoolExtra `
        $extraArguments `
        "persistent_network_lease" `
        $PersistentNetworkLease
    Add-BoolExtra `
        $extraArguments `
        "persistent_heartbeat_socket" `
        $PersistentHeartbeatSocket
    Add-BoolExtra `
        $extraArguments `
        "frequent_notification_refresh" `
        $FrequentNotificationRefresh
    Add-BoolExtra $extraArguments "monitor_target_gms" $MonitorGms
    Add-BoolExtra $extraArguments "monitor_target_whatsapp" $MonitorWhatsApp
    Add-BoolExtra `
        $extraArguments `
        "monitor_target_whatsapp_business" `
        $MonitorWhatsAppBusiness
}

$arguments = [System.Collections.Generic.List[string]]::new()
$arguments.AddRange([string[]]@(
    "-s", $DeviceSerial,
    "shell", "content", "call",
    "--user", "0",
    "--uri", $providerUri,
    "--method", $method
))
$arguments.AddRange($extraArguments)

$adbArguments = [string[]]$arguments.ToArray()
try {
    $output = & adb.exe @adbArguments 2>&1
}
catch {
    throw "adb ContentProvider 调用失败：$($_.Exception.Message)"
}
if ($LASTEXITCODE -ne 0) {
    throw "adb ContentProvider 调用失败：`n$($output | Out-String)"
}

$raw = ($output | Out-String).Trim()
$result = Parse-ProviderResult $raw

$result.GetEnumerator() | ForEach-Object {
    "{0}={1}" -f $_.Key, $_.Value
}

if (-not $result.Contains("ok") -or $result["ok"] -ne "true") {
    $reason = if ($result.Contains("reason")) {
        $result["reason"]
    }
    else {
        "unknown"
    }
    throw "努昂诺塔拒绝或未完成配置：$reason"
}
