param(
    [string]$Serial = "127.0.0.1:5052"
)

$ErrorActionPreference = "Continue"
$ProgressPreference = "SilentlyContinue"

$Stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$OutputDirectory = Join-Path $PWD "iqoo-probe-$Stamp"
$ReportPath = Join-Path $OutputDirectory "iqoo-readonly-$Stamp.txt"
$FullLogPath = Join-Path $OutputDirectory "iqoo-logcat-all-$Stamp.txt"
$ZipPath = Join-Path $PWD "iqoo-readonly-$Stamp.zip"
$HashPath = Join-Path $OutputDirectory "SHA256SUMS.txt"

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
Set-Content -LiteralPath $ReportPath -Value "" -Encoding UTF8

$Packages = @(
    "com.yubegreen.luonnotar",
    "com.yubegreen.pushtrace",
    "com.google.android.gms",
    "com.google.android.gsf",
    "com.android.vending",
    "com.whatsapp",
    "com.whatsapp.w4b",
    "ch.protonvpn.android",
    "com.tailscale.ipn"
)

$TargetPattern = @(
    "com\.yubegreen\.luonnotar",
    "com\.yubegreen\.pushtrace",
    "com\.google\.android\.gms",
    "com\.google\.android\.gsf",
    "com\.android\.vending",
    "com\.whatsapp",
    "com\.whatsapp\.w4b",
    "ch\.protonvpn\.android",
    "com\.tailscale\.ipn"
) -join "|"

$FreezePattern = @(
    "QuickFrozen",
    "quick.?frozen",
    "fast_freezer",
    "single_cleaner",
    "am_app_frozen",
    "am_app_unfrozen",
    "am_uid_stopped",
    "mIsFrozen",
    "cached_apps_freezer",
    "freeze_fgapp",
    "com\.vivo\.pem"
) -join "|"

$PushPattern = @(
    "GCM_CONN_ALARM",
    "GCM_HB_ALARM",
    "FcmRetry",
    "C2DM_RECEIVE",
    "FirebaseInstanceIdReceiver",
    "GcmFGService",
    "MessageService",
    "XmppLifecycleWorker",
    "notification_enqueue",
    "mtalk",
    "\bMCS\b"
) -join "|"

function Add-ReportLine {
    param(
        [AllowNull()]
        [object]$Value
    )

    if ($null -eq $Value) {
        return
    }

    Add-Content -LiteralPath $ReportPath -Value $Value.ToString() -Encoding UTF8
}

function Add-ReportLines {
    param(
        [AllowNull()]
        [object[]]$Values
    )

    $Items = @($Values)

    if ($Items.Count -eq 0) {
        Add-ReportLine "(无输出)"
        return
    }

    foreach ($Item in $Items) {
        if ($null -ne $Item) {
            Add-ReportLine $Item
        }
    }
}

function Write-Section {
    param(
        [string]$Title
    )

    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host $Title -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan

    Add-ReportLine ""
    Add-ReportLine "============================================================"
    Add-ReportLine $Title
    Add-ReportLine "============================================================"
}

function Get-AdbOutput {
    param(
        [string[]]$AdbArgs
    )

    $Result = & adb -s $Serial @AdbArgs 2>&1

    return @(
        $Result | ForEach-Object {
            $_.ToString().TrimEnd("`r")
        }
    )
}

function Invoke-AdbRead {
    param(
        [string]$Label,
        [string[]]$AdbArgs
    )

    Add-ReportLine ""
    Add-ReportLine "-- $Label --"
    Add-ReportLine ("$ adb -s {0} {1}" -f $Serial, ($AdbArgs -join " "))

    $Output = Get-AdbOutput -AdbArgs $AdbArgs
    Add-ReportLines $Output

    return $Output
}

function Add-FilteredLines {
    param(
        [string]$Label,
        [object[]]$InputLines,
        [string]$Pattern,
        [int]$ContextBefore = 0,
        [int]$ContextAfter = 0,
        [int]$First = 0,
        [int]$Last = 0
    )

    Add-ReportLine ""
    Add-ReportLine "-- $Label --"

    $Matches = @(
        $InputLines |
            Select-String `
                -Pattern $Pattern `
                -CaseSensitive:$false `
                -Context $ContextBefore, $ContextAfter |
            Out-String -Stream
    )

    if ($First -gt 0) {
        $Matches = @($Matches | Select-Object -First $First)
    }

    if ($Last -gt 0) {
        $Matches = @($Matches | Select-Object -Last $Last)
    }

    if ($Matches.Count -eq 0) {
        Add-ReportLine "(无匹配)"
    }
    else {
        Add-ReportLines $Matches
    }
}

Write-Host "目标设备：$Serial" -ForegroundColor Yellow
Write-Host "本脚本只读取信息：不写设置、不清日志、不冻结、不重启。" -ForegroundColor Yellow

$DeviceState = (& adb -s $Serial get-state 2>$null | Out-String).Trim()

if ($DeviceState -ne "device") {
    Write-Host "设备不可用：$Serial" -ForegroundColor Red
    & adb devices -l
    exit 1
}

Write-Section "采集信息"

@(
    "设备：$Serial",
    "Windows 时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')",
    "当前目录：$PWD",
    "输出目录：$OutputDirectory",
    "报告：$ReportPath",
    "完整 Logcat：$FullLogPath",
    "只读采集：是"
) | ForEach-Object {
    Add-ReportLine $_
}

Write-Section "ADB 与设备身份"

Add-ReportLine ""
Add-ReportLine "-- ADB 版本 --"
Add-ReportLines (& adb version 2>&1)

Add-ReportLine ""
Add-ReportLine "-- 当前设备列表 --"
Add-ReportLines (& adb devices -l 2>&1)

Invoke-AdbRead -Label "设备状态" -AdbArgs @("get-state") | Out-Null
Invoke-AdbRead -Label "设备时间" -AdbArgs @("shell", "date") | Out-Null
Invoke-AdbRead -Label "设备运行时间" -AdbArgs @("shell", "uptime") | Out-Null
Invoke-AdbRead -Label "Shell 身份" -AdbArgs @("shell", "id") | Out-Null

$BasicProperties = @(
    "ro.product.manufacturer",
    "ro.product.brand",
    "ro.product.model",
    "ro.product.device",
    "ro.build.version.release",
    "ro.build.version.sdk",
    "ro.build.version.security_patch",
    "ro.build.display.id",
    "ro.build.fingerprint",
    "ro.vivo.product.version",
    "ro.vivo.os.version",
    "ro.vivo.os.build.display.id",
    "sys.boot_completed"
)

foreach ($PropertyName in $BasicProperties) {
    Invoke-AdbRead `
        -Label "getprop $PropertyName" `
        -AdbArgs @("shell", "getprop", $PropertyName) |
        Out-Null
}

Write-Section "vivo、PEM 与冻结器入口"

$AllProperties = Get-AdbOutput -AdbArgs @("shell", "getprop")
Add-FilteredLines `
    -Label "相关系统属性" `
    -InputLines $AllProperties `
    -Pattern "vivo|iqoo|pem|quick.?frozen|freez|frozen|freeze_fgapp|cached_apps_freezer|doze|background|power"

$CommandServices = Get-AdbOutput -AdbArgs @("shell", "cmd", "-l")
Add-FilteredLines `
    -Label "相关 cmd 服务" `
    -InputLines $CommandServices `
    -Pattern "freez|frozen|pem|vivo|power|sps|nrms|abe|activity|deviceidle|appops"

$DumpsysServices = Get-AdbOutput -AdbArgs @("shell", "dumpsys", "-l")
Add-FilteredLines `
    -Label "相关 dumpsys 服务" `
    -InputLines $DumpsysServices `
    -Pattern "freez|frozen|pem|quick|vivo|power|sps|nrms|abe|activity|deviceidle"

$BinderServices = Get-AdbOutput -AdbArgs @("shell", "service", "list")
Add-FilteredLines `
    -Label "相关 Binder 服务" `
    -InputLines $BinderServices `
    -Pattern "freez|frozen|pem|quick|vivo|power|sps|nrms|abe"

$SystemPackages = Get-AdbOutput -AdbArgs @("shell", "pm", "list", "packages", "-s")
Add-FilteredLines `
    -Label "相关系统包" `
    -InputLines $SystemPackages `
    -Pattern "com\.vivo.*(pem|abe|sps|power|battery|freez)|pem|quickfrozen|frozen|freeze"

Write-Section "AOSP Cached Apps Freezer"

Invoke-AdbRead `
    -Label "settings global cached_apps_freezer" `
    -AdbArgs @("shell", "settings", "get", "global", "cached_apps_freezer") |
    Out-Null

Invoke-AdbRead `
    -Label "device_config use_freezer" `
    -AdbArgs @(
        "shell",
        "device_config",
        "get",
        "activity_manager_native_boot",
        "use_freezer"
    ) |
    Out-Null

$ActivityDump = Get-AdbOutput -AdbArgs @("shell", "dumpsys", "activity")
Add-FilteredLines `
    -Label "ActivityManager 冻结状态" `
    -InputLines $ActivityDump `
    -Pattern "Apps frozen:|freezer|frozen|mUseFreezer" `
    -ContextBefore 2 `
    -ContextAfter 80 `
    -Last 800

Write-Section "冻结、后台与电源相关设置"

foreach ($SettingsTable in @("global", "secure", "system")) {
    $SettingsOutput = Get-AdbOutput -AdbArgs @(
        "shell",
        "settings",
        "list",
        $SettingsTable
    )

    Add-FilteredLines `
        -Label "settings list $SettingsTable" `
        -InputLines $SettingsOutput `
        -Pattern "freez|frozen|pem|quick|background|power|doze|idle|sleep|standby|battery"
}

Write-Section "电源、息屏与 Doze"

$PowerDump = Get-AdbOutput -AdbArgs @("shell", "dumpsys", "power")
Add-FilteredLines `
    -Label "PowerManager 关键状态" `
    -InputLines $PowerDump `
    -Pattern "Wakefulness|mWakefulness|Display Power|Screen|Doze|Suspend|Wake Lock|mHolding|interactive|Last wake|Last sleep" `
    -ContextBefore 1 `
    -ContextAfter 2 `
    -Last 700

Invoke-AdbRead `
    -Label "Battery" `
    -AdbArgs @("shell", "dumpsys", "battery") |
    Out-Null

$DeviceIdleDump = Invoke-AdbRead `
    -Label "DeviceIdle 完整状态" `
    -AdbArgs @("shell", "dumpsys", "deviceidle")

$DeviceIdleWhitelist = Get-AdbOutput -AdbArgs @(
    "shell",
    "cmd",
    "deviceidle",
    "whitelist"
)

$NetPolicyDump = Get-AdbOutput -AdbArgs @(
    "shell",
    "dumpsys",
    "netpolicy"
)

Write-Section "目标应用包状态"

foreach ($PackageName in $Packages) {
    Add-ReportLine ""
    Add-ReportLine "############################################################"
    Add-ReportLine "包：$PackageName"
    Add-ReportLine "############################################################"

    $PackagePath = Get-AdbOutput -AdbArgs @(
        "shell",
        "pm",
        "path",
        $PackageName
    )

    $Installed = @(
        $PackagePath | Where-Object {
            $_ -like "package:*"
        }
    )

    if ($Installed.Count -eq 0) {
        Add-ReportLine "未安装或无法查询。"
        continue
    }

    Add-ReportLine "-- APK 路径 --"
    Add-ReportLines $Installed

    $UidOutput = Get-AdbOutput -AdbArgs @(
        "shell",
        "pm",
        "list",
        "packages",
        "-U",
        $PackageName
    )

    Add-ReportLine "-- UID --"
    Add-ReportLines $UidOutput

    $Uid = $null

    foreach ($UidLine in $UidOutput) {
        if ($UidLine -match "uid:(\d+)") {
            $Uid = $Matches[1]
            break
        }
    }

    $PackageDump = Get-AdbOutput -AdbArgs @(
        "shell",
        "dumpsys",
        "package",
        $PackageName
    )

    Add-FilteredLines `
        -Label "版本、安装时间与 User 0 状态" `
        -InputLines $PackageDump `
        -Pattern "userId=|versionName=|versionCode=|firstInstallTime=|lastUpdateTime=|pkgFlags=|privateFlags=|enabled=|stopped=|hidden=|suspended=|installPermissionsFixed=|User 0:" `
        -ContextBefore 1 `
        -ContextAfter 4 `
        -Last 350

    Invoke-AdbRead `
        -Label "AppOps" `
        -AdbArgs @(
            "shell",
            "cmd",
            "appops",
            "get",
            $PackageName,
            "RUN_IN_BACKGROUND",
            "RUN_ANY_IN_BACKGROUND",
            "WAKE_LOCK"
        ) |
        Out-Null

    Invoke-AdbRead `
        -Label "Standby Bucket" `
        -AdbArgs @(
            "shell",
            "am",
            "get-standby-bucket",
            $PackageName
        ) |
        Out-Null

    Add-FilteredLines `
        -Label "DeviceIdle 白名单匹配" `
        -InputLines $DeviceIdleWhitelist `
        -Pattern ([regex]::Escape($PackageName))

    if ($null -ne $Uid) {
        Add-FilteredLines `
            -Label "NetPolicy 中 UID $Uid" `
            -InputLines $NetPolicyDump `
            -Pattern "(^|[^0-9])$([regex]::Escape($Uid))([^0-9]|$)" `
            -ContextBefore 4 `
            -ContextAfter 4 `
            -Last 180
    }

    Invoke-AdbRead `
        -Label "PID" `
        -AdbArgs @("shell", "pidof", $PackageName) |
        Out-Null
}

Write-Section "目标进程列表"

$ProcessList = Get-AdbOutput -AdbArgs @(
    "shell",
    "ps",
    "-A",
    "-o",
    "USER,UID,PID,PPID,PRI,NI,STAT,NAME"
)

if (
    ($ProcessList -join "`n") -match
    "unknown option|invalid option|bad -o"
) {
    $ProcessList = Get-AdbOutput -AdbArgs @(
        "shell",
        "ps",
        "-A"
    )
}

$TargetProcesses = @(
    $ProcessList | Where-Object {
        $_ -match $TargetPattern
    }
)

Add-ReportLines $TargetProcesses

$ProcessNames = @(
    $TargetProcesses |
        ForEach-Object {
            $Columns = $_ -split "\s+"

            if ($Columns.Count -gt 0) {
                $Columns[-1]
            }
        } |
        Where-Object {
            $_ -match $TargetPattern
        } |
        Sort-Object -Unique
)

Write-Section "目标进程 /proc 状态"

foreach ($ProcessName in $ProcessNames) {
    $PidOutput = Get-AdbOutput -AdbArgs @(
        "shell",
        "pidof",
        $ProcessName
    )

    $ProcessIds = @(
        (($PidOutput -join " ") -split "\s+") |
            Where-Object {
                $_ -match "^\d+$"
            }
    )

    foreach ($ProcessId in $ProcessIds) {
        Add-ReportLine ""
        Add-ReportLine "------------------------------------------------------------"
        Add-ReportLine "进程：$ProcessName"
        Add-ReportLine "PID：$ProcessId"
        Add-ReportLine "------------------------------------------------------------"

        $CmdlineBytes = Get-AdbOutput -AdbArgs @(
            "shell",
            "cat",
            "/proc/$ProcessId/cmdline"
        )

        $CmdlineText = ($CmdlineBytes -join " ").Replace([char]0, " ")
        Add-ReportLine "cmdline: $CmdlineText"

        Invoke-AdbRead `
            -Label "oom_score_adj" `
            -AdbArgs @(
                "shell",
                "cat",
                "/proc/$ProcessId/oom_score_adj"
            ) |
            Out-Null

        Invoke-AdbRead `
            -Label "oom_score" `
            -AdbArgs @(
                "shell",
                "cat",
                "/proc/$ProcessId/oom_score"
            ) |
            Out-Null

        Invoke-AdbRead `
            -Label "wchan" `
            -AdbArgs @(
                "shell",
                "cat",
                "/proc/$ProcessId/wchan"
            ) |
            Out-Null

        $ProcessStatus = Get-AdbOutput -AdbArgs @(
            "shell",
            "cat",
            "/proc/$ProcessId/status"
        )

        Add-FilteredLines `
            -Label "/proc/$ProcessId/status" `
            -InputLines $ProcessStatus `
            -Pattern "^(Name|State|Tgid|Pid|PPid|TracerPid|Uid|Gid|Threads|VmRSS|VmSwap|voluntary_ctxt_switches|nonvoluntary_ctxt_switches):"

        Invoke-AdbRead `
            -Label "/proc/$ProcessId/cgroup" `
            -AdbArgs @(
                "shell",
                "cat",
                "/proc/$ProcessId/cgroup"
            ) |
            Out-Null

        Invoke-AdbRead `
            -Label "/proc/$ProcessId/schedstat" `
            -AdbArgs @(
                "shell",
                "cat",
                "/proc/$ProcessId/schedstat"
            ) |
            Out-Null
    }
}

Write-Section "ActivityManager 进程等级"

$ActivityProcesses = Get-AdbOutput -AdbArgs @(
    "shell",
    "dumpsys",
    "activity",
    "processes"
)

Add-FilteredLines `
    -Label "目标进程 adj、procState 与 OOM 信息" `
    -InputLines $ActivityProcesses `
    -Pattern $TargetPattern `
    -ContextBefore 10 `
    -ContextAfter 14 `
    -Last 2400

Write-Section "Binder Anchor 与服务连接"

$GmsServices = Get-AdbOutput -AdbArgs @(
    "shell",
    "dumpsys",
    "activity",
    "services",
    "com.google.android.gms"
)

Add-FilteredLines `
    -Label "Google Play 服务 Binder 连接" `
    -InputLines $GmsServices `
    -Pattern "luonnotar|ConnectionRecord|ServiceRecord|binding|bound|client|persistent|location" `
    -ContextBefore 10 `
    -ContextAfter 14 `
    -Last 2000

$LuonnotarServices = Get-AdbOutput -AdbArgs @(
    "shell",
    "dumpsys",
    "activity",
    "services",
    "com.yubegreen.luonnotar"
)

Add-FilteredLines `
    -Label "努昂诺塔 Keeper 与前台服务" `
    -InputLines $LuonnotarServices `
    -Pattern "FcmGuardianService|keeper|ConnectionRecord|ServiceRecord|binding|bound|client|foreground|isForeground" `
    -ContextBefore 10 `
    -ContextAfter 14 `
    -Last 1800

Write-Section "AlarmManager 与 GCM 心跳"

$AlarmDump = Get-AdbOutput -AdbArgs @(
    "shell",
    "dumpsys",
    "alarm"
)

Add-FilteredLines `
    -Label "目标应用、GCM、MCS 与心跳闹钟" `
    -InputLines $AlarmDump `
    -Pattern "$TargetPattern|GCM|MCS|heartbeat|GCM_HB_ALARM|GCM_CONN_ALARM|C2DM" `
    -ContextBefore 7 `
    -ContextAfter 9 `
    -Last 2400

Write-Section "JobScheduler 与恢复任务"

$JobDump = Get-AdbOutput -AdbArgs @(
    "shell",
    "dumpsys",
    "jobscheduler"
)

Add-FilteredLines `
    -Label "目标任务与 FCM 恢复任务" `
    -InputLines $JobDump `
    -Pattern "$TargetPattern|FcmRecoveryWorker|luonnotar_fcm_recovery|GCM|heartbeat" `
    -ContextBefore 8 `
    -ContextAfter 12 `
    -Last 2400

Write-Section "VPN、Tailscale 与网络"

Invoke-AdbRead `
    -Label "网络接口" `
    -AdbArgs @("shell", "ip", "addr", "show") |
    Out-Null

Invoke-AdbRead `
    -Label "路由表" `
    -AdbArgs @("shell", "ip", "route", "show") |
    Out-Null

$ConnectivityDump = Get-AdbOutput -AdbArgs @(
    "shell",
    "dumpsys",
    "connectivity"
)

Add-FilteredLines `
    -Label "Connectivity VPN 状态" `
    -InputLines $ConnectivityDump `
    -Pattern "VPN|TRANSPORT_VPN|TUN|NetworkAgent|NetworkCapabilities|tailscale|proton|ch\.protonvpn|com\.tailscale" `
    -ContextBefore 6 `
    -ContextAfter 10 `
    -Last 2000

Invoke-AdbRead `
    -Label "VPN dumpsys" `
    -AdbArgs @("shell", "dumpsys", "vpn") |
    Out-Null

$SocketDump = Get-AdbOutput -AdbArgs @(
    "shell",
    "ss",
    "-nt"
)

Add-FilteredLines `
    -Label "mtalk 与 Tailscale TCP 连接" `
    -InputLines $SocketDump `
    -Pattern "State|5228|5229|5230|100\." `
    -Last 1000

Write-Section "Wi-Fi 状态"

$WifiDump = Get-AdbOutput -AdbArgs @(
    "shell",
    "dumpsys",
    "wifi"
)

Add-FilteredLines `
    -Label "Wi-Fi 连接信息" `
    -InputLines $WifiDump `
    -Pattern "Wi-Fi is|mWifiInfo|SSID|BSSID|Supplicant state|NetworkId|Link speed|Frequency|RSSI|DHCP|IP address|Default route|validated" `
    -ContextBefore 2 `
    -ContextAfter 3 `
    -Last 1100

Write-Section "完整 Logcat"

Write-Host "读取完整 Logcat，不会清空缓冲区……" -ForegroundColor Yellow
Add-ReportLine "正在读取完整 Logcat；未执行 logcat -c。"

& adb -s $Serial logcat -d -b all -v threadtime 2>&1 |
    ForEach-Object {
        $_.ToString().TrimEnd("`r")
    } |
    Set-Content -LiteralPath $FullLogPath -Encoding UTF8

Add-ReportLine "完整 Logcat：$FullLogPath"

Write-Section "QuickFrozen、GMS、FCM 与推送日志"

$CombinedLogPattern = "$FreezePattern|$PushPattern|$TargetPattern"

$FilteredLog = @(
    Get-Content -LiteralPath $FullLogPath |
        Select-String `
            -Pattern $CombinedLogPattern `
            -CaseSensitive:$false |
        Select-Object -Last 7000 |
        Out-String -Stream
)

Add-ReportLines $FilteredLog

Write-Section "Logcat 缓冲区信息"

Invoke-AdbRead `
    -Label "logcat -g" `
    -AdbArgs @("logcat", "-g") |
    Out-Null

Write-Section "完成"

@(
    "设备：$Serial",
    "采集结束：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')",
    "报告：$ReportPath",
    "完整 Logcat：$FullLogPath"
) | ForEach-Object {
    Add-ReportLine $_
}

$FilesToHash = @(
    $ReportPath,
    $FullLogPath
)

$HashLines = foreach ($FilePath in $FilesToHash) {
    if (Test-Path -LiteralPath $FilePath) {
        $FileHash = Get-FileHash -LiteralPath $FilePath -Algorithm SHA256
        "$($FileHash.Hash)  $($FileHash.Path)"
    }
}

$HashLines | Set-Content -LiteralPath $HashPath -Encoding UTF8

Write-Host "压缩采集结果……" -ForegroundColor Yellow

Compress-Archive `
    -LiteralPath @(
        $ReportPath,
        $FullLogPath,
        $HashPath
    ) `
    -DestinationPath $ZipPath `
    -CompressionLevel Optimal `
    -Force

Write-Host ""
Write-Host "采集完成。" -ForegroundColor Green
Write-Host "输出目录：$OutputDirectory"
Write-Host "ZIP：$ZipPath" -ForegroundColor Green
