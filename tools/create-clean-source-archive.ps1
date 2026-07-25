[CmdletBinding()]
param(
    [string]$ProjectRoot = "",
    [string]$OutputPath = "",
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Join-Path $PSScriptRoot ".."
}

function Get-RelativeArchivePath {
    param(
        [string]$RootWithSeparator,
        [string]$FullName
    )

    if (-not $FullName.StartsWith($RootWithSeparator, [StringComparison]::OrdinalIgnoreCase)) {
        throw "File is outside the project root: $FullName"
    }
    return $FullName.Substring($RootWithSeparator.Length).Replace("\", "/")
}

function Test-ExcludedArchivePath {
    param([string]$RelativePath)

    $parts = @($RelativePath -split "[\\/]")
    $excludedDirectories = @(
        ".git",
        ".gradle",
        ".idea",
        ".kotlin",
        "signing",
        "build",
        "build-evidence",
        "test-output",
        "device-test-output",
        "adb-output",
        "logs",
        "log",
        "exports"
    )
    foreach ($part in $parts) {
        if ($excludedDirectories -contains $part.ToLowerInvariant()) {
            return $true
        }
    }

    $name = $parts[-1]
    $lowerName = $name.ToLowerInvariant()
    $explicitNames = @(
        "keystore.properties",
        "local.properties",
        "release-signing-backup.md"
    )
    if ($explicitNames -contains $lowerName) {
        return $true
    }

    $extension = [System.IO.Path]::GetExtension($name).ToLowerInvariant()
    if (
        $parts.Count -gt 1 -and
        $parts[0].ToLowerInvariant() -eq "docs" -and
        @(".png", ".jpg", ".jpeg", ".webp", ".gif") -contains $extension
    ) {
        return $true
    }
    if (@(
        ("." + "jks"),
        ("." + "keystore"),
        ".apk",
        ".aab",
        ".apks",
        ".idsig",
        ".dex",
        ".class",
        ".log",
        ".hprof",
        ".tmp",
        ".zip"
    ) -contains $extension) {
        return $true
    }

    if ($lowerName -match "(^|[-_.])(logcat|dumpsys|timeline|device-data|test-device)([-_.]|$)") {
        return $true
    }
    return $false
}

function Test-SafeSigningReferenceLine {
    param(
        [string]$Line,
        [string[]]$CredentialTerms
    )

    $trimmed = $Line.Trim()
    foreach ($term in $CredentialTerms) {
        $escaped = [regex]::Escape($term)
        if ($trimmed -match "^`"$escaped`"\s*,?$") {
            return $true
        }
        if (
            $trimmed -match "getProperty\s*\(\s*`"$escaped`"\s*\)" -and
            $trimmed -notmatch "=\s*`"[^`"]+`"\s*$"
        ) {
            return $true
        }
    }
    return $false
}

function Find-ArchiveSecurityFindings {
    param([string]$ArchivePath)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $findings = [System.Collections.Generic.List[string]]::new()
    $credentialTerms = @(
        ("pass" + "word"),
        ("store" + "Pass" + "word"),
        ("key" + "Pass" + "word")
    )
    $privateKeyMarker = "PRIVATE" + " KEY"
    $jksMarker = "." + "jks"
    $keystoreMarker = "." + "keystore"
    $textExtensions = @(
        ".kt", ".kts", ".java", ".xml", ".gradle", ".properties",
        ".md", ".txt", ".ps1", ".bat", ".cmd", ".json", ".yaml",
        ".yml", ".toml", ".pro", ".cfg", ".ini", ".sh", ".pem",
        ".key", ".crt"
    )

    $archive = [System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        foreach ($entry in $archive.Entries) {
            $entryName = $entry.FullName
            $lowerEntryName = $entryName.ToLowerInvariant()
            if (
                $lowerEntryName.EndsWith($jksMarker) -or
                $lowerEntryName.EndsWith($keystoreMarker) -or
                $lowerEntryName.EndsWith("/keystore.properties") -or
                $lowerEntryName.EndsWith("/local.properties") -or
                $lowerEntryName -match "(^|/)signing/"
            ) {
                $findings.Add("$entryName`: prohibited sensitive path")
                continue
            }
            if ($entry.Length -eq 0) {
                continue
            }
            $entryLeaf = [System.IO.Path]::GetFileName($entryName)
            $entryExtension = [System.IO.Path]::GetExtension($entryLeaf).ToLowerInvariant()
            if (
                $entryLeaf -ne ".gitignore" -and
                $textExtensions -notcontains $entryExtension
            ) {
                continue
            }

            $stream = $entry.Open()
            $reader = [System.IO.StreamReader]::new(
                $stream,
                [System.Text.Encoding]::UTF8,
                $true,
                4096,
                $false
            )
            try {
                $lineNumber = 0
                while (-not $reader.EndOfStream) {
                    $line = $reader.ReadLine()
                    $lineNumber++
                    if ($line.IndexOf($privateKeyMarker, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                        $findings.Add("$entryName`:$lineNumber`: private-key material marker")
                        continue
                    }
                    if (
                        $line.IndexOf($jksMarker, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
                        $line.IndexOf($keystoreMarker, [StringComparison]::OrdinalIgnoreCase) -ge 0
                    ) {
                        if ($entryLeaf -ne ".gitignore") {
                            $findings.Add("$entryName`:$lineNumber`: keystore reference")
                        }
                        continue
                    }

                    $containsCredentialTerm = $false
                    foreach ($term in $credentialTerms) {
                        if ($line.IndexOf($term, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                            $containsCredentialTerm = $true
                            break
                        }
                    }
                    if (
                        $containsCredentialTerm -and
                        -not (Test-SafeSigningReferenceLine -Line $line -CredentialTerms $credentialTerms)
                    ) {
                        $findings.Add("$entryName`:$lineNumber`: credential-like content")
                    }
                }
            } finally {
                $reader.Dispose()
            }
        }
    } finally {
        $archive.Dispose()
    }
    return @($findings)
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$resolvedRoot = (Resolve-Path -LiteralPath $ProjectRoot -ErrorAction Stop).Path.TrimEnd("\", "/")
$rootWithSeparator = $resolvedRoot + [System.IO.Path]::DirectorySeparatorChar
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $parent = Split-Path -Parent $resolvedRoot
    $leaf = Split-Path -Leaf $resolvedRoot
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputPath = Join-Path $parent "$leaf-clean-source-$timestamp.zip"
}
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)

if (Test-Path -LiteralPath $resolvedOutput) {
    if (-not $Force) {
        throw "Output already exists. Choose another path or pass -Force: $resolvedOutput"
    }
    Remove-Item -LiteralPath $resolvedOutput -Force
}
$outputParent = Split-Path -Parent $resolvedOutput
if (-not (Test-Path -LiteralPath $outputParent)) {
    New-Item -ItemType Directory -Path $outputParent -Force | Out-Null
}

$sourceFiles = [System.Collections.Generic.List[object]]::new()
foreach ($file in Get-ChildItem -LiteralPath $resolvedRoot -Recurse -Force -File) {
    if ($file.FullName.Equals($resolvedOutput, [StringComparison]::OrdinalIgnoreCase)) {
        continue
    }
    $relative = Get-RelativeArchivePath -RootWithSeparator $rootWithSeparator -FullName $file.FullName
    if (-not (Test-ExcludedArchivePath -RelativePath $relative)) {
        $sourceFiles.Add([pscustomobject]@{
            File = $file
            RelativePath = $relative
        })
    }
}

if ($sourceFiles.Count -eq 0) {
    throw "No source files remained after applying archive exclusions."
}

$zip = [System.IO.Compression.ZipFile]::Open(
    $resolvedOutput,
    [System.IO.Compression.ZipArchiveMode]::Create
)
try {
    foreach ($source in $sourceFiles) {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip,
            $source.File.FullName,
            $source.RelativePath,
            [System.IO.Compression.CompressionLevel]::Optimal
        ) | Out-Null
    }
} catch {
    $zip.Dispose()
    if (Test-Path -LiteralPath $resolvedOutput) {
        Remove-Item -LiteralPath $resolvedOutput -Force
    }
    throw
} finally {
    $zip.Dispose()
}

$securityFindings = @(Find-ArchiveSecurityFindings -ArchivePath $resolvedOutput)
if ($securityFindings.Count -gt 0) {
    Remove-Item -LiteralPath $resolvedOutput -Force
    throw "Sensitive content scan failed; archive removed:`n$($securityFindings -join "`n")"
}

$archiveInfo = Get-Item -LiteralPath $resolvedOutput
$archiveHash = Get-FileHash -LiteralPath $resolvedOutput -Algorithm SHA256
Write-Host "CLEAN_SOURCE_ARCHIVE_OK"
Write-Host "Path: $($archiveInfo.FullName)"
Write-Host "Files: $($sourceFiles.Count)"
Write-Host "Bytes: $($archiveInfo.Length)"
Write-Host "SHA-256: $($archiveHash.Hash)"
