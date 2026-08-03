[CmdletBinding(DefaultParameterSetName = "Status")]
param(
    [string]$DeviceSerial = "127.0.0.1:5132",

    [Parameter(Mandatory = $true, ParameterSetName = "Start")]
    [Alias("StartSession")]
    [ValidateNotNullOrEmpty()]
    [string]$Start,

    [Parameter(Mandatory = $true, ParameterSetName = "Mark")]
    [ValidateNotNullOrEmpty()]
    [string]$Mark,

    [Parameter(Mandatory = $true, ParameterSetName = "Stop")]
    [switch]$Stop,

    [Parameter(ParameterSetName = "Status")]
    [switch]$StatusOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$providerUri = "content://com.yubegreen.luonnotar.adb_runtime_config"
$method = switch ($PSCmdlet.ParameterSetName) {
    "Start" { "experiment_start" }
    "Mark" { "experiment_mark" }
    "Stop" { "experiment_stop" }
    default { "status" }
}

function Parse-ProviderResult {
    param([Parameter(Mandatory = $true)][string]$Text)

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

$arguments = [System.Collections.Generic.List[string]]::new()
$arguments.AddRange([string[]]@(
    "-s", $DeviceSerial,
    "shell", "content", "call",
    "--user", "0",
    "--uri", $providerUri,
    "--method", $method
))

switch ($PSCmdlet.ParameterSetName) {
    "Start" {
        $arguments.Add("--extra")
        $arguments.Add(("session_name:s:{0}" -f $Start))
    }
    "Mark" {
        $arguments.Add("--extra")
        $arguments.Add(("mark_label:s:{0}" -f $Mark))
    }
}

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

$result = Parse-ProviderResult (($output | Out-String).Trim())
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
    throw "努昂诺塔拒绝实验会话操作：$reason"
}
