param(
    [Parameter(Mandatory = $true)]
    [string]$SendEventsPath,

    [string]$DeviceSerial = "127.0.0.1:5132",
    [int]$DeliveryTimeoutSeconds = 25,
    [int]$RecoveryCooldownSeconds = 180,
    [int]$MaxRecoveriesPerIncident = 2,
    [int]$StatusPollSeconds = 2,
    [int]$ListenerHeartbeatMaxAgeSeconds = 390,
    [int]$PreRecoveryScanWaitMilliseconds = 2000,
    [int]$SendHistoryGraceSeconds = 300,
    [int]$PersistedArrivalArmMaxAgeSeconds = 300,
    [string]$OutputDirectory = "$HOME\Desktop\Luonnotar-Push-Recovery",
    [switch]$EnableRecovery,
    [switch]$ObserveOnly,
    [switch]$AllowRecoveryBeforeFirstArrival
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if (-not (Test-Path -LiteralPath $SendEventsPath)) {
    throw "找不到发送事件 CSV：$SendEventsPath"
}
if ($DeliveryTimeoutSeconds -lt 5) {
    throw "DeliveryTimeoutSeconds 不能小于 5 秒"
}
if ($RecoveryCooldownSeconds -lt 30) {
    throw "RecoveryCooldownSeconds 不能小于 30 秒"
}
if ($MaxRecoveriesPerIncident -lt 1 -or $MaxRecoveriesPerIncident -gt 10) {
    throw "MaxRecoveriesPerIncident 必须在 1 到 10 之间"
}
if ($StatusPollSeconds -lt 1 -or $StatusPollSeconds -gt 30) {
    throw "StatusPollSeconds 必须在 1 到 30 之间"
}
if ($ListenerHeartbeatMaxAgeSeconds -lt 60) {
    throw "ListenerHeartbeatMaxAgeSeconds 不能小于 60 秒"
}
if ($PreRecoveryScanWaitMilliseconds -lt 500 -or $PreRecoveryScanWaitMilliseconds -gt 10000) {
    throw "PreRecoveryScanWaitMilliseconds 必须在 500 到 10000 之间"
}
if ($SendHistoryGraceSeconds -lt 0 -or $SendHistoryGraceSeconds -gt 3600) {
    throw "SendHistoryGraceSeconds 必须在 0 到 3600 之间"
}
if (
    $PersistedArrivalArmMaxAgeSeconds -lt 0 -or
    $PersistedArrivalArmMaxAgeSeconds -gt 3600
) {
    throw "PersistedArrivalArmMaxAgeSeconds 必须在 0 到 3600 之间"
}
if ($EnableRecovery -and $ObserveOnly) {
    throw "EnableRecovery 与 ObserveOnly 不能同时使用"
}
$recoveryEnabled = $EnableRecovery -and -not $ObserveOnly
$watchStartedUtc = [datetime]::UtcNow
$watcherRevision = '1.7.12-r3'

$requiredColumns = @(
    'utc_time', 'event', 'status', 'sequence', 'message'
)
$invariant = [System.Globalization.CultureInfo]::InvariantCulture
$dateStyles = [System.Globalization.DateTimeStyles]::AssumeUniversal -bor `
    [System.Globalization.DateTimeStyles]::AdjustToUniversal
$notificationReceiver =
    'com.yubegreen.luonnotar/.receiver.AdbNotificationDiagnosticsReceiver'
$notificationStatusAction =
    'com.yubegreen.luonnotar.action.ADB_NOTIFICATION_STATUS'
$notificationPrivacyAction =
    'com.yubegreen.luonnotar.action.ADB_NOTIFICATION_PRIVACY'
$notificationScanAction =
    'com.yubegreen.luonnotar.action.ADB_NOTIFICATION_SCAN_ACTIVE'

function Resolve-SenderTimeZone {
    foreach ($id in @('New Zealand Standard Time', 'Pacific/Auckland')) {
        try {
            return [System.TimeZoneInfo]::FindSystemTimeZoneById($id)
        }
        catch {
            # Try the next Windows/IANA identifier.
        }
    }
    throw "找不到 Pacific/Auckland 时区；无法与 APK 的 PUSH_TEST 时间戳精确配对"
}

$senderTimeZone = Resolve-SenderTimeZone

function Normalize-PushTestText {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) { return '' }
    $withoutFormatCharacters = [regex]::Replace($Value, '\p{Cf}', '')
    return [regex]::Replace(
        $withoutFormatCharacters,
        '[\s\p{Z}]+',
        ' '
    ).Trim()
}

function Convert-PushTestMessage {
    param([string]$Message)

    $normalized = Normalize-PushTestText -Value $Message
    if ([string]::IsNullOrWhiteSpace($normalized)) { return $null }
    $match = [regex]::Match(
        $normalized,
        '^PUSH_TEST_(\d+) (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})(?:\.(\d{3}))?$'
    )
    if (-not $match.Success) { return $null }

    $sequence = 0L
    if (-not [long]::TryParse($match.Groups[1].Value, [ref]$sequence)) {
        return $null
    }

    $timestampText = $match.Groups[2].Value
    $format = 'yyyy-MM-dd HH:mm:ss'
    if ($match.Groups[3].Success) {
        $timestampText += '.' + $match.Groups[3].Value
        $format += '.fff'
    }

    try {
        $unspecified = [datetime]::ParseExact(
            $timestampText,
            $format,
            $invariant,
            [System.Globalization.DateTimeStyles]::None
        )
        $unspecified = [datetime]::SpecifyKind(
            $unspecified,
            [System.DateTimeKind]::Unspecified
        )
        $utc = [System.TimeZoneInfo]::ConvertTimeToUtc(
            $unspecified,
            $senderTimeZone
        )
        $epochMs = ([System.DateTimeOffset]$utc).ToUnixTimeMilliseconds()
        return [pscustomobject]@{
            Sequence = $sequence
            SenderEpochMs = $epochMs
            Key = "$epochMs`:$sequence"
        }
    }
    catch {
        return $null
    }
}

function Parse-UtcCsvTime {
    param([string]$Value)
    try {
        return [datetime]::ParseExact(
            $Value,
            'yyyy-MM-dd HH:mm:ss.fff',
            $invariant,
            $dateStyles
        )
    }
    catch {
        return $null
    }
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$sessionStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runLog = Join-Path $OutputDirectory "watch-$sessionStamp.csv"
$liveLogcat = Join-Path $OutputDirectory "logcat-live-$sessionStamp.txt"
$logcatError = Join-Path $OutputDirectory "logcat-error-$sessionStamp.txt"
$watchMarker = "PUSH_WATCH_START_$sessionStamp"

'wall_time,event,sequence,sender_epoch_ms,detail' |
    Set-Content -LiteralPath $runLog -Encoding utf8

function Write-RunEvent {
    param(
        [string]$Event,
        [long]$Sequence,
        [long]$SenderEpochMs,
        [string]$Detail
    )

    $escaped = $Detail.Replace('"', '""')
    $line = '"{0}","{1}","{2}","{3}","{4}"' -f `
        (Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff'), `
        $Event, $Sequence, $SenderEpochMs, $escaped
    Add-Content -LiteralPath $runLog -Value $line -Encoding utf8
    Write-Host (
        '[{0}] {1} #{2} epoch={3} {4}' -f `
            (Get-Date -Format 'HH:mm:ss'), `
            $Event, $Sequence, $SenderEpochMs, $Detail
    )
}

function Invoke-AdbText {
    param([string[]]$Arguments)

    $output = & adb @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb 失败：adb $($Arguments -join ' ')`n$output"
    }
    return ($output | Out-String).Trim()
}

function Get-GmsPersistentPid {
    $output = & adb -s $DeviceSerial shell pidof `
        com.google.android.gms.persistent 2>$null
    if ($LASTEXITCODE -ne 0) { return '' }
    return ($output | Out-String).Trim()
}

function Read-SharedTextSnapshot {
    param([string]$Path)

    $share = [System.IO.FileShare]::ReadWrite -bor `
        [System.IO.FileShare]::Delete
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $stream = $null
        $reader = $null
        try {
            $stream = [System.IO.File]::Open(
                $Path,
                [System.IO.FileMode]::Open,
                [System.IO.FileAccess]::Read,
                $share
            )
            $reader = New-Object System.IO.StreamReader($stream, $true)
            return $reader.ReadToEnd()
        }
        catch {
            if ($attempt -eq 3) { throw }
            Start-Sleep -Milliseconds (50 * $attempt)
        }
        finally {
            if ($reader) { $reader.Dispose() }
            elseif ($stream) { $stream.Dispose() }
        }
    }
}

function Read-CompleteSendRows {
    $text = Read-SharedTextSnapshot -Path $SendEventsPath
    if ([string]::IsNullOrWhiteSpace($text)) { return @() }

    # iCloud/atomic writers may expose a final half-written line. Only parse
    # through the last newline; the partial tail is retried on the next poll.
    $lastLf = $text.LastIndexOf("`n")
    if ($lastLf -lt 0) { return @() }
    $completeText = $text.Substring(0, $lastLf + 1)
    $completeText = $completeText.TrimStart([char]0xFEFF)
    $firstLineEnd = $completeText.IndexOf("`n")
    if ($firstLineEnd -lt 0) { return @() }
    $headerLine = $completeText.Substring(0, $firstLineEnd).TrimEnd("`r").TrimStart([char]0xFEFF)
    $headers = $headerLine.Split(',')
    foreach ($column in $requiredColumns) {
        if ($headers -notcontains $column) {
            throw "发送事件 CSV 缺少列：$column"
        }
    }
    $rows = @($completeText | ConvertFrom-Csv)
    return $rows
}

function Convert-WireBoolean {
    param([string]$Value)
    return $Value -eq 'true'
}

function Convert-WireLong {
    param([string]$Value)
    $number = 0L
    if ([long]::TryParse($Value, [ref]$number)) { return $number }
    return 0L
}

function Invoke-NotificationDiagnostic {
    param([string]$Action)

    # STATUS/PRIVACY are idempotent and may transiently return result=0 on
    # OriginOS while the explicit receiver process is being rebound. Retry
    # those reads, but never duplicate an active-notification scan request.
    $maxAttempts = if ($Action -eq $notificationScanAction) { 1 } else { 3 }
    $lastOutput = ''

    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        $output = Invoke-AdbText -Arguments @(
            '-s', $DeviceSerial,
            'shell', 'am', 'broadcast',
            '--include-stopped-packages',
            '--receiver-foreground',
            '-a', $Action,
            '-n', $notificationReceiver
        )
        $lastOutput = $output
        $match = [regex]::Match(
            $output,
            'data="((?:\\.|[^"\\])*)"'
        )
        if ($match.Success) { break }

        if ($attempt -lt $maxAttempts) {
            Start-Sleep -Milliseconds (200 * $attempt)
        }
    }

    if (-not $match.Success) {
        throw "努昂诺塔通知诊断入口没有返回状态：$lastOutput"
    }
    $wire = $match.Groups[1].Value
    $wire = $wire.Replace('\"', '"').Replace('\\', '\')
    $values = @{}
    foreach ($pair in $wire.Split(';')) {
        $index = $pair.IndexOf('=')
        if ($index -le 0) { continue }
        $key = $pair.Substring(0, $index)
        $value = $pair.Substring($index + 1)
        $values[$key] = $value
    }
    if (-not $values.ContainsKey('schema')) {
        throw "努昂诺塔通知诊断状态格式无效：$wire"
    }
    return [pscustomobject]@{
        Schema = (Convert-WireLong -Value ([string]$values['schema']))
        VersionName = [string]$values['versionName']
        Ok = (Convert-WireBoolean -Value ([string]$values['ok']))
        PrivacyAcknowledged = (Convert-WireBoolean -Value `
            ([string]$values['privacyAcknowledged']))
        ListenerPersistedConnected = (Convert-WireBoolean -Value `
            ([string]$values['listenerPersistedConnected']))
        ListenerRuntimeConnected = (Convert-WireBoolean -Value `
            ([string]$values['listenerRuntimeConnected']))
        ListenerPid = (Convert-WireLong -Value ([string]$values['listenerPid']))
        HeartbeatAgeMs = (Convert-WireLong -Value ([string]$values['heartbeatAgeMs']))
        LiveSequence = (Convert-WireLong -Value ([string]$values['liveSequence']))
        LiveSenderEpochMs = (Convert-WireLong -Value `
            ([string]$values['liveSenderEpochMs']))
        LiveSeenWall = (Convert-WireLong -Value ([string]$values['liveSeenWall']))
        ScanSequence = (Convert-WireLong -Value ([string]$values['scanSequence']))
        ScanSenderEpochMs = (Convert-WireLong -Value `
            ([string]$values['scanSenderEpochMs']))
        ScanNotificationPostWall = (Convert-WireLong -Value `
            ([string]$values['scanNotificationPostWall']))
        ScanSeenWall = (Convert-WireLong -Value ([string]$values['scanSeenWall']))
        ScanQueued = (Convert-WireBoolean -Value ([string]$values['scanQueued']))
        Raw = $wire
    }
}

function Remove-PendingThroughWatermark {
    param(
        [long]$SenderEpochMs,
        [string]$EvidenceEvent,
        [long]$Sequence,
        [string]$Detail
    )

    if ($SenderEpochMs -le 0L) { return 0 }
    $removed = 0
    foreach ($key in @($script:pending.Keys)) {
        if ($script:pending[$key].SenderEpochMs -le $SenderEpochMs) {
            $script:pending.Remove($key) | Out-Null
            $script:recoveryAttempts.Remove($key) | Out-Null
            $script:limitReported.Remove($key) | Out-Null
            $removed++
        }
    }
    Write-RunEvent `
        -Event $EvidenceEvent `
        -Sequence $Sequence `
        -SenderEpochMs $SenderEpochMs `
        -Detail ("removedPending=$removed; $Detail")
    return $removed
}

function Sync-NotificationStatus {
    param(
        [object]$Status,
        [switch]$AllowArmFromLiveAdvance
    )

    $script:lastNotificationStatus = $Status
    $script:listenerConnected =
        $Status.ListenerPersistedConnected -and `
        $Status.ListenerRuntimeConnected
    $script:privacyAcknowledged = $Status.PrivacyAcknowledged

    if ($Status.LiveSenderEpochMs -gt $script:lastLiveWatermarkEpoch) {
        $advancedAfterBaseline =
            $Status.LiveSenderEpochMs -gt $script:baselineLiveWatermarkEpoch
        $script:lastLiveWatermarkEpoch = $Status.LiveSenderEpochMs
        if ($advancedAfterBaseline -and $AllowArmFromLiveAdvance) {
            $script:firstArrivalObserved = $true
        }
        Remove-PendingThroughWatermark `
            -SenderEpochMs $Status.LiveSenderEpochMs `
            -EvidenceEvent 'ARRIVAL_WATERMARK_SYNCED' `
            -Sequence $Status.LiveSequence `
            -Detail (
                "source=persisted_live; seenWall=$($Status.LiveSeenWall); " +
                "armed=$($script:firstArrivalObserved)"
            ) | Out-Null
    }

    if ($Status.ScanSenderEpochMs -gt $script:lastScanWatermarkEpoch) {
        $script:lastScanWatermarkEpoch = $Status.ScanSenderEpochMs
        Remove-PendingThroughWatermark `
            -SenderEpochMs $Status.ScanSenderEpochMs `
            -EvidenceEvent 'ACTIVE_SCAN_UPPER_BOUND_SYNCED' `
            -Sequence $Status.ScanSequence `
            -Detail (
                "postWall=$($Status.ScanNotificationPostWall); " +
                "scanSeenWall=$($Status.ScanSeenWall); " +
                "armsRecovery=false; sessionArmed=$($script:firstArrivalObserved)"
            ) | Out-Null
    }
}

function Get-RecoveryHealthFailure {
    param([object]$Status)

    if ($null -eq $Status -or -not $Status.Ok) {
        return 'diagnostic_status_unavailable'
    }
    if (-not $Status.PrivacyAcknowledged) {
        return 'privacy_not_acknowledged'
    }
    if (-not $Status.ListenerPersistedConnected) {
        return 'listener_not_persisted_connected'
    }
    if (-not $Status.ListenerRuntimeConnected) {
        return 'listener_not_runtime_connected'
    }
    $maxHeartbeatAgeMs = [long]$ListenerHeartbeatMaxAgeSeconds * 1000L
    if (
        $Status.HeartbeatAgeMs -lt 0L -or
        $Status.HeartbeatAgeMs -gt $maxHeartbeatAgeMs
    ) {
        return "listener_heartbeat_stale_$($Status.HeartbeatAgeMs)ms"
    }
    return ''
}

$state = Invoke-AdbText -Arguments @('-s', $DeviceSerial, 'get-state')
if ($state -ne 'device') {
    throw "设备不可用：$DeviceSerial（状态：$state）"
}

$privacyStatus = Invoke-NotificationDiagnostic -Action $notificationPrivacyAction
if (-not $privacyStatus.Ok -or $privacyStatus.Schema -lt 1) {
    throw "努昂诺塔通知诊断不可用；请先安装 1.7.11 或更高版本"
}
if (-not $privacyStatus.PrivacyAcknowledged) {
    throw "努昂诺塔尚未确认通知隐私说明，自动恢复禁止启动"
}
$initialStatus = Invoke-NotificationDiagnostic -Action $notificationStatusAction

$logcatArgs = @(
    '-s', $DeviceSerial,
    'logcat',
    '-b', 'main',
    '-b', 'system',
    '-b', 'crash',
    '-b', 'radio',
    '-v', 'threadtime',
    '-s', 'Luonnotar:I', 'LUONNOTAR_WATCH:I', '*:S'
)
$logcatProcess = Start-Process `
    -FilePath 'adb' `
    -ArgumentList $logcatArgs `
    -RedirectStandardOutput $liveLogcat `
    -RedirectStandardError $logcatError `
    -NoNewWindow `
    -PassThru

$logStream = $null
$logReader = $null

try {
    while (-not (Test-Path -LiteralPath $liveLogcat)) {
        if ($logcatProcess.HasExited) {
            throw "ADB logcat 无法启动：$($logcatProcess.ExitCode)"
        }
        Start-Sleep -Milliseconds 100
    }
    $logShare = [System.IO.FileShare]::ReadWrite -bor `
        [System.IO.FileShare]::Delete
    $logStream = [System.IO.File]::Open(
        $liveLogcat,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        $logShare
    )
    $logReader = New-Object System.IO.StreamReader($logStream, $true)

    # A normal adb logcat first drains historical buffers. Emit a unique marker
    # and ignore every evidence line before it.
    Invoke-AdbText -Arguments @(
        '-s', $DeviceSerial, 'shell', 'log', '-t',
        'LUONNOTAR_WATCH', $watchMarker
    ) | Out-Null

    $script:pending = @{}
    $script:recoveryAttempts = @{}
    $script:limitReported = New-Object 'System.Collections.Generic.HashSet[string]'
    $observedSendKeys = New-Object 'System.Collections.Generic.HashSet[string]'
    $logMarkerObserved = $false
    $nowEpochMs =
        [System.DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $persistedArrivalAgeMs = if ($initialStatus.LiveSeenWall -gt 0L) {
        [Math]::Max(0L, $nowEpochMs - $initialStatus.LiveSeenWall)
    }
    else {
        [long]::MaxValue
    }
    $script:firstArrivalObserved =
        $PersistedArrivalArmMaxAgeSeconds -gt 0 -and
        $initialStatus.LiveSenderEpochMs -gt 0L -and
        $persistedArrivalAgeMs -le
            ([long]$PersistedArrivalArmMaxAgeSeconds * 1000L)
    $script:listenerConnected =
        $initialStatus.ListenerPersistedConnected -and `
        $initialStatus.ListenerRuntimeConnected
    $script:privacyAcknowledged = $initialStatus.PrivacyAcknowledged
    $script:lastNotificationStatus = $initialStatus
    $script:baselineLiveWatermarkEpoch = $initialStatus.LiveSenderEpochMs
    $script:lastLiveWatermarkEpoch = $initialStatus.LiveSenderEpochMs
    $script:lastScanWatermarkEpoch = $initialStatus.ScanSenderEpochMs
    $lastStatusPollUtc = [datetime]::MinValue
    $lastActionUtc = [datetime]::MinValue
    $lastSuppressionUtc = [datetime]::MinValue
    $lastSuppressionReason = ''
    $lastSendReadError = ''
    $sendCutoffUtc = $watchStartedUtc.AddSeconds(-$SendHistoryGraceSeconds)

    Write-RunEvent `
        -Event 'WATCH_STARTED' `
        -Sequence 0 `
        -SenderEpochMs 0 `
        -Detail (
            "device=$DeviceSerial; apk=$($initialStatus.VersionName); " +
            "watcherRevision=$watcherRevision; " +
            "timeout=${DeliveryTimeoutSeconds}s; recoveryEnabled=$recoveryEnabled; " +
            "maxRecoveries=$MaxRecoveriesPerIncident; " +
            "senderZone=$($senderTimeZone.Id); " +
            "baselineLive=$($initialStatus.LiveSenderEpochMs); " +
            "baselineScan=$($initialStatus.ScanSenderEpochMs); " +
            "sendHistoryGrace=${SendHistoryGraceSeconds}s; " +
            "persistedArrivalAgeMs=$persistedArrivalAgeMs; " +
            "armedFromRecentPersistedLive=$($script:firstArrivalObserved)"
        )

    while ($true) {
        if ($logcatProcess.HasExited) {
            throw "ADB logcat 进程意外退出：$($logcatProcess.ExitCode)"
        }

        try {
            $rows = @(Read-CompleteSendRows)
            $lastSendReadError = ''
            foreach ($row in $rows) {
                if (
                    $row.event -ne 'SEND_RESULT' -or
                    $row.status -ne 'ENTER_SENT'
                ) {
                    continue
                }
                $parsed = Convert-PushTestMessage -Message $row.message
                if ($null -eq $parsed) { continue }
                if (-not $observedSendKeys.Add($parsed.Key)) { continue }

                $sentUtc = Parse-UtcCsvTime -Value $row.utc_time
                if ($null -eq $sentUtc) { continue }
                if ($sentUtc -lt $sendCutoffUtc) { continue }

                # SMB/iCloud may expose the CSV row after logcat/persisted state
                # already confirmed the same message. Never re-add such a send
                # as pending, or an end-of-stream race can create a false timeout.
                $confirmedWatermarkEpoch = [Math]::Max(
                    $script:lastLiveWatermarkEpoch,
                    $script:lastScanWatermarkEpoch
                )
                if ($parsed.SenderEpochMs -le $confirmedWatermarkEpoch) {
                    $confirmedBy = if (
                        $parsed.SenderEpochMs -le $script:lastLiveWatermarkEpoch
                    ) {
                        'live_watermark'
                    }
                    else {
                        'active_scan_watermark'
                    }
                    Write-RunEvent `
                        -Event 'SEND_ALREADY_CONFIRMED' `
                        -Sequence $parsed.Sequence `
                        -SenderEpochMs $parsed.SenderEpochMs `
                        -Detail (
                            "confirmedBy=$confirmedBy; " +
                            "watermarkEpoch=$confirmedWatermarkEpoch; " +
                            $row.message
                        )
                    continue
                }

                $script:pending[$parsed.Key] = [pscustomobject]@{
                    Key = $parsed.Key
                    Sequence = $parsed.Sequence
                    SenderEpochMs = $parsed.SenderEpochMs
                    SentUtc = $sentUtc
                    Message = $row.message
                }
                Write-RunEvent `
                    -Event 'SEND_OBSERVED' `
                    -Sequence $parsed.Sequence `
                    -SenderEpochMs $parsed.SenderEpochMs `
                    -Detail $row.message
            }
        }
        catch {
            $errorText = $_.Exception.Message
            if ($errorText -ne $lastSendReadError) {
                Write-RunEvent `
                    -Event 'SEND_FILE_READ_RETRY' `
                    -Sequence 0 `
                    -SenderEpochMs 0 `
                    -Detail $errorText
                $lastSendReadError = $errorText
            }
        }

        while (-not $logReader.EndOfStream) {
            $line = $logReader.ReadLine()
            if ($line -match [regex]::Escape($watchMarker)) {
                $logMarkerObserved = $true
                Write-RunEvent `
                    -Event 'LOG_STREAM_ARMED' `
                    -Sequence 0 `
                    -SenderEpochMs 0 `
                    -Detail $watchMarker
                continue
            }
            if (-not $logMarkerObserved) { continue }

            if ($line -match 'notification_listener_connected') {
                $script:listenerConnected = $true
            }
            elseif ($line -match 'notification_listener_disconnected') {
                $script:listenerConnected = $false
            }

            if ($line -match 'push_test_arrival_observed') {
                if ($line -notmatch 'sequence=(\d+)') { continue }
                $arrivedSequence = [long]$Matches[1]
                if ($line -notmatch 'senderEpochMs=(\d+)') { continue }
                $arrivedSenderEpochMs = [long]$Matches[1]

                $script:firstArrivalObserved = $true
                $script:listenerConnected = $true
                if ($arrivedSenderEpochMs -gt $script:lastLiveWatermarkEpoch) {
                    $script:lastLiveWatermarkEpoch = $arrivedSenderEpochMs
                }
                Remove-PendingThroughWatermark `
                    -SenderEpochMs $arrivedSenderEpochMs `
                    -EvidenceEvent 'ARRIVAL_OBSERVED' `
                    -Sequence $arrivedSequence `
                    -Detail 'source=live_logcat; armed=true' | Out-Null
                continue
            }

            if ($line -match 'push_test_active_scan_upper_bound_observed') {
                if ($line -notmatch 'sequence=(\d+)') { continue }
                $scanSequence = [long]$Matches[1]
                if ($line -notmatch 'senderEpochMs=(\d+)') { continue }
                $scanSenderEpochMs = [long]$Matches[1]
                if ($scanSenderEpochMs -gt $script:lastScanWatermarkEpoch) {
                    $script:lastScanWatermarkEpoch = $scanSenderEpochMs
                }
                Remove-PendingThroughWatermark `
                    -SenderEpochMs $scanSenderEpochMs `
                    -EvidenceEvent 'ACTIVE_SCAN_UPPER_BOUND_OBSERVED' `
                    -Sequence $scanSequence `
                    -Detail (
                        "source=active_scan_logcat; armsRecovery=false; " +
                        "sessionArmed=$($script:firstArrivalObserved)"
                    ) | Out-Null
            }
        }

        if (
            ([datetime]::UtcNow - $lastStatusPollUtc).TotalSeconds -ge
                $StatusPollSeconds
        ) {
            try {
                $status = Invoke-NotificationDiagnostic `
                    -Action $notificationStatusAction
                Sync-NotificationStatus `
                    -Status $status `
                    -AllowArmFromLiveAdvance
                $lastStatusPollUtc = [datetime]::UtcNow
            }
            catch {
                $script:lastNotificationStatus = $null
                $script:listenerConnected = $false
                Write-RunEvent `
                    -Event 'NOTIFICATION_STATUS_POLL_FAILED' `
                    -Sequence 0 `
                    -SenderEpochMs 0 `
                    -Detail $_.Exception.Message
                $lastStatusPollUtc = [datetime]::UtcNow
            }
        }

        if ($script:pending.Count -gt 0) {
            $oldest = @($script:pending.Values | Sort-Object SentUtc)[0]
            $ageSeconds = ([datetime]::UtcNow - $oldest.SentUtc).TotalSeconds
            $armed =
                $script:firstArrivalObserved -or
                $AllowRecoveryBeforeFirstArrival
            $cooldownReady = (
                ([datetime]::UtcNow - $lastActionUtc).TotalSeconds -ge
                    $RecoveryCooldownSeconds
            )

            if (
                $ageSeconds -ge $DeliveryTimeoutSeconds -and
                $cooldownReady
            ) {
                $detail = 'age={0:N1}s; pending={1}' -f `
                    $ageSeconds, $script:pending.Count

                $freshStatus = $null
                $healthFailure = ''
                try {
                    $freshStatus = Invoke-NotificationDiagnostic `
                        -Action $notificationStatusAction
                    Sync-NotificationStatus `
                        -Status $freshStatus `
                        -AllowArmFromLiveAdvance
                    if (-not $script:pending.ContainsKey($oldest.Key)) {
                        Write-RunEvent `
                            -Event 'RECOVERY_CANCELLED_PERSISTED_ARRIVAL' `
                            -Sequence $oldest.Sequence `
                            -SenderEpochMs $oldest.SenderEpochMs `
                            -Detail $detail
                        Start-Sleep -Milliseconds 500
                        continue
                    }
                    $healthFailure = Get-RecoveryHealthFailure `
                        -Status $freshStatus
                }
                catch {
                    $healthFailure =
                        'diagnostic_status_exception:' + $_.Exception.Message
                }

                if ([string]::IsNullOrWhiteSpace($healthFailure) -and -not $armed) {
                    $healthFailure = 'no_live_arrival_observed_in_this_watch_session'
                }

                if ([string]::IsNullOrWhiteSpace($healthFailure)) {
                    try {
                        $scanRequest = Invoke-NotificationDiagnostic `
                            -Action $notificationScanAction
                        if ($scanRequest.ScanQueued) {
                            Start-Sleep -Milliseconds `
                                $PreRecoveryScanWaitMilliseconds
                            $afterScanStatus = Invoke-NotificationDiagnostic `
                                -Action $notificationStatusAction
                            Sync-NotificationStatus `
                                -Status $afterScanStatus `
                                -AllowArmFromLiveAdvance
                            if (-not $script:pending.ContainsKey($oldest.Key)) {
                                Write-RunEvent `
                                    -Event 'RECOVERY_CANCELLED_ACTIVE_SCAN_CONFIRMED' `
                                    -Sequence $oldest.Sequence `
                                    -SenderEpochMs $oldest.SenderEpochMs `
                                    -Detail $detail
                                Start-Sleep -Milliseconds 500
                                continue
                            }
                            $healthFailure = Get-RecoveryHealthFailure `
                                -Status $afterScanStatus
                        }
                        else {
                            $healthFailure = 'active_scan_not_queued'
                        }
                    }
                    catch {
                        $healthFailure =
                            'active_scan_exception:' + $_.Exception.Message
                    }
                }

                if (-not [string]::IsNullOrWhiteSpace($healthFailure)) {
                    $shouldLogSuppression =
                        $healthFailure -ne $lastSuppressionReason -or
                        ([datetime]::UtcNow - $lastSuppressionUtc).TotalSeconds -ge 10
                    if ($shouldLogSuppression) {
                        Write-RunEvent `
                            -Event 'RECOVERY_SUPPRESSED_EVIDENCE_UNHEALTHY' `
                            -Sequence $oldest.Sequence `
                            -SenderEpochMs $oldest.SenderEpochMs `
                            -Detail ("reason=$healthFailure; $detail")
                        $lastSuppressionReason = $healthFailure
                        $lastSuppressionUtc = [datetime]::UtcNow
                    }
                    Start-Sleep -Milliseconds 500
                    continue
                }

                $attemptCount = 0
                if ($script:recoveryAttempts.ContainsKey($oldest.Key)) {
                    $attemptCount = [int]$script:recoveryAttempts[$oldest.Key]
                }
                if ($attemptCount -ge $MaxRecoveriesPerIncident) {
                    if (-not $script:limitReported.Contains($oldest.Key)) {
                        $script:limitReported.Add($oldest.Key) | Out-Null
                        $limitEvent = if ($recoveryEnabled) {
                            'RECOVERY_LIMIT_REACHED'
                        }
                        else {
                            'WOULD_RECOVERY_LIMIT_REACHED'
                        }
                        Write-RunEvent `
                            -Event $limitEvent `
                            -Sequence $oldest.Sequence `
                            -SenderEpochMs $oldest.SenderEpochMs `
                            -Detail ("attempts=$attemptCount; $detail")
                    }
                    $lastActionUtc = [datetime]::UtcNow
                    Start-Sleep -Milliseconds 500
                    continue
                }

                $lastActionUtc = [datetime]::UtcNow
                if (-not $recoveryEnabled) {
                    $attemptCount++
                    $script:recoveryAttempts[$oldest.Key] = $attemptCount
                    Write-RunEvent `
                        -Event 'WOULD_RECOVER' `
                        -Sequence $oldest.Sequence `
                        -SenderEpochMs $oldest.SenderEpochMs `
                        -Detail (
                            "attempt=$attemptCount; $detail; privacy=true; " +
                            "listenerRuntime=true; persistentWatermarkRechecked=true; " +
                            "activeScanRechecked=true"
                        )
                }
                else {
                    $attemptCount++
                    $script:recoveryAttempts[$oldest.Key] = $attemptCount
                    Write-RunEvent `
                        -Event 'DELIVERY_TIMEOUT' `
                        -Sequence $oldest.Sequence `
                        -SenderEpochMs $oldest.SenderEpochMs `
                        -Detail ("attempt=$attemptCount; $detail")

                    $captureStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
                    $capturePath = Join-Path $OutputDirectory `
                        "pre-recovery-$captureStamp-seq-$($oldest.Sequence).txt"
                    & adb -s $DeviceSerial logcat `
                        -b main -b system -b crash -b radio `
                        -v threadtime -d 2>&1 |
                        Set-Content -LiteralPath $capturePath -Encoding utf8
                    $captureExit = $LASTEXITCODE

                    # Re-scan immediately before force-stop. The earlier scan may
                    # already be stale after evidence capture.
                    $finalScan = Invoke-NotificationDiagnostic `
                        -Action $notificationScanAction
                    if (-not $finalScan.ScanQueued) {
                        Write-RunEvent `
                            -Event 'RECOVERY_ABORTED_FINAL_SCAN_NOT_QUEUED' `
                            -Sequence $oldest.Sequence `
                            -SenderEpochMs $oldest.SenderEpochMs `
                            -Detail $detail
                        continue
                    }
                    Start-Sleep -Milliseconds $PreRecoveryScanWaitMilliseconds

                    # Final status check is intentionally immediately adjacent
                    # to force-stop. No stale cached listener state is trusted.
                    $finalStatus = Invoke-NotificationDiagnostic `
                        -Action $notificationStatusAction
                    Sync-NotificationStatus `
                        -Status $finalStatus `
                        -AllowArmFromLiveAdvance
                    $finalFailure = Get-RecoveryHealthFailure `
                        -Status $finalStatus
                    if (-not $script:pending.ContainsKey($oldest.Key)) {
                        Write-RunEvent `
                            -Event 'RECOVERY_CANCELLED_FINAL_WATERMARK' `
                            -Sequence $oldest.Sequence `
                            -SenderEpochMs $oldest.SenderEpochMs `
                            -Detail $detail
                        continue
                    }
                    if (-not [string]::IsNullOrWhiteSpace($finalFailure)) {
                        Write-RunEvent `
                            -Event 'RECOVERY_ABORTED_FINAL_HEALTH_CHECK' `
                            -Sequence $oldest.Sequence `
                            -SenderEpochMs $oldest.SenderEpochMs `
                            -Detail ("reason=$finalFailure; $detail")
                        continue
                    }

                    $oldPid = Get-GmsPersistentPid
                    Invoke-AdbText -Arguments @(
                        '-s', $DeviceSerial, 'shell', 'log', '-t',
                        'LUONNOTAR_TEST',
                        "ADB_ASSISTED_RECOVERY_START_SEQ_$($oldest.Sequence)_OLDPID_$oldPid"
                    ) | Out-Null
                    Invoke-AdbText -Arguments @(
                        '-s', $DeviceSerial, 'shell', 'am', 'force-stop',
                        '--user', '0', 'com.google.android.gms'
                    ) | Out-Null

                    $newPid = ''
                    for ($i = 0; $i -lt 20; $i++) {
                        Start-Sleep -Seconds 1
                        $newPid = Get-GmsPersistentPid
                        if ($newPid -and $newPid -ne $oldPid) { break }
                    }

                    Invoke-AdbText -Arguments @(
                        '-s', $DeviceSerial, 'shell', 'log', '-t',
                        'LUONNOTAR_TEST',
                        "ADB_ASSISTED_RECOVERY_END_SEQ_$($oldest.Sequence)_NEWPID_$newPid"
                    ) | Out-Null

                    $restarted = (
                        -not [string]::IsNullOrWhiteSpace($newPid) -and
                        $newPid -ne $oldPid
                    )
                    $eventName = if ($restarted) {
                        'GMS_PROCESS_REPLACED'
                    }
                    else {
                        'GMS_RESTART_NOT_OBSERVED'
                    }
                    Write-RunEvent `
                        -Event $eventName `
                        -Sequence $oldest.Sequence `
                        -SenderEpochMs $oldest.SenderEpochMs `
                        -Detail (
                            "oldPid=$oldPid; newPid=$newPid; " +
                            "captureExit=$captureExit; evidence=$capturePath"
                        )
                }
            }
        }

        Start-Sleep -Milliseconds 500
    }
}
finally {
    if ($logReader) { $logReader.Dispose() }
    if ($logStream) { $logStream.Dispose() }
    if ($logcatProcess -and -not $logcatProcess.HasExited) {
        Stop-Process `
            -Id $logcatProcess.Id `
            -Force `
            -ErrorAction SilentlyContinue
    }
    Write-RunEvent `
        -Event 'WATCH_STOPPED' `
        -Sequence 0 `
        -SenderEpochMs 0 `
        -Detail 'cleanup complete'
}
