[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string[]]$InputPath,
    [string]$SendEventsCsv = "",
    [string]$OutputDirectory = "",
    [string[]]$SourceName = @(),
    [int]$BacklogThresholdMs = 10000,
    [int]$BacklogReleaseGapMs = 5000
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$PythonScript = Join-Path $PSScriptRoot "analyze-luonnotar-push-session.py"
if (-not (Test-Path -LiteralPath $PythonScript -PathType Leaf)) {
    throw "找不到分析器：$PythonScript"
}

$Python = Get-Command py.exe -ErrorAction SilentlyContinue
$Prefix = @()
if ($Python) {
    $PythonExe = $Python.Source
    $Prefix = @("-3")
}
else {
    $Python = Get-Command python.exe -ErrorAction SilentlyContinue
    if (-not $Python) {
        $Python = Get-Command python3.exe -ErrorAction SilentlyContinue
    }
    if (-not $Python) {
        throw "找不到 Python 3。请安装 Python，或确保 py.exe/python.exe 在 PATH 中。"
    }
    $PythonExe = $Python.Source
}

$Arguments = [System.Collections.Generic.List[string]]::new()
$Arguments.AddRange([string[]]$Prefix)
$Arguments.Add($PythonScript)
foreach ($Path in $InputPath) {
    $Arguments.Add("--input")
    $Arguments.Add((Resolve-Path -LiteralPath $Path).Path)
}
foreach ($Name in $SourceName) {
    $Arguments.Add("--source-name")
    $Arguments.Add($Name)
}
if (-not [string]::IsNullOrWhiteSpace($SendEventsCsv)) {
    $Arguments.Add("--send-events")
    $Arguments.Add((Resolve-Path -LiteralPath $SendEventsCsv).Path)
}
if (-not [string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $Arguments.Add("--output")
    $Arguments.Add($OutputDirectory)
}
$Arguments.Add("--backlog-threshold-ms")
$Arguments.Add($BacklogThresholdMs.ToString())
$Arguments.Add("--backlog-release-gap-ms")
$Arguments.Add($BacklogReleaseGapMs.ToString())

$ProcessArguments = [string[]]$Arguments.ToArray()
& $PythonExe @ProcessArguments
if ($LASTEXITCODE -ne 0) {
    throw "日志分析器执行失败，退出码：$LASTEXITCODE"
}
