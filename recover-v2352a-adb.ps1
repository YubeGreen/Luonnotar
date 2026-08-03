param(
    [int]$StartPort = 30000,
    [int]$EndPort = 65535,
    [int]$Parallel = 96
)

$ErrorActionPreference = 'Stop'

$Adb = 'C:\Users\ysbss\Downloads\platform-tools-latest-windows\platform-tools\adb.exe'
$PhoneIp = '100.111.89.64'
$FixedTarget = "${PhoneIp}:5555"

$SshExe = (Get-Command ssh.exe -ErrorAction Stop).Source
$SshTarget = 'u0_a440@127.0.0.1'
$SshPort = 8024

function Invoke-External {
    param(
        [Parameter(Mandatory)]
        [string]$FilePath,

        [Parameter(Mandatory)]
        [string[]]$Arguments,

        [int]$TimeoutMs = 8000
    )

    $OutFile = [IO.Path]::GetTempFileName()
    $ErrFile = [IO.Path]::GetTempFileName()
    $Process = $null

    try {
        $Process = Start-Process `
            -FilePath $FilePath `
            -ArgumentList $Arguments `
            -RedirectStandardOutput $OutFile `
            -RedirectStandardError $ErrFile `
            -WindowStyle Hidden `
            -PassThru

        $Finished = $Process.WaitForExit($TimeoutMs)

        if (-not $Finished) {
            Stop-Process `
                -Id $Process.Id `
                -Force `
                -ErrorAction SilentlyContinue

            [void]$Process.WaitForExit(1000)
        }

        if ($Finished) {
            $Process.WaitForExit()
        }

        $ExitCode = $null

        if ($Finished) {
            $ExitCode = $Process.ExitCode
        }

        return [pscustomobject]@{
            Finished = $Finished
            ExitCode = $ExitCode
            StdOut   = [IO.File]::ReadAllText($OutFile)
            StdErr   = [IO.File]::ReadAllText($ErrFile)
        }
    }
    finally {
        Remove-Item `
            -LiteralPath $OutFile, $ErrFile `
            -Force `
            -ErrorAction SilentlyContinue
    }
}

function Test-TcpPort {
    param(
        [Parameter(Mandatory)]
        [string]$Address,

        [Parameter(Mandatory)]
        [int]$Port,

        [int]$TimeoutMs = 3000
    )

    $Client = [Net.Sockets.TcpClient]::new()
    $Async = $null

    try {
        $Async = $Client.BeginConnect(
            $Address,
            $Port,
            $null,
            $null
        )

        $Completed = $Async.AsyncWaitHandle.WaitOne($TimeoutMs)

        if (-not $Completed) {
            return $false
        }

        $Client.EndConnect($Async)
        return $Client.Connected
    }
    catch {
        return $false
    }
    finally {
        if ($Async) {
            $Async.AsyncWaitHandle.Close()
        }

        $Client.Dispose()
    }
}

function Test-RealAdb {
    param(
        [Parameter(Mandatory)]
        [string]$Target,

        [int]$TimeoutMs = 7000
    )

    $Token = "__ADB_RECOVERY_$([guid]::NewGuid().ToString('N'))__"

    $Result = Invoke-External `
        -FilePath $Adb `
        -Arguments @(
            '-s',
            $Target,
            'shell',
            'echo',
            $Token
        ) `
        -TimeoutMs $TimeoutMs

    $Healthy = (
        $Result.Finished -and
        $Result.StdOut.Contains($Token)
    )

    return [pscustomobject]@{
        Healthy = $Healthy
        Result  = $Result
    }
}

function Connect-And-Probe {
    param(
        [Parameter(Mandatory)]
        [string]$Target
    )

    $Connect = Invoke-External `
        -FilePath $Adb `
        -Arguments @(
            'connect',
            $Target
        ) `
        -TimeoutMs 8000

    $ConnectText = (
        (
            $Connect.StdOut +
            ' ' +
            $Connect.StdErr
        ) -replace '\s+', ' '
    ).Trim()

    $Probe = Test-RealAdb `
        -Target $Target `
        -TimeoutMs 7000

    return [pscustomobject]@{
        Healthy     = $Probe.Healthy
        ConnectText = $ConnectText
        Probe       = $Probe
    }
}

function Stop-TunnelProcess {
    param(
        $Process
    )

    if ($null -eq $Process) {
        return
    }

    if ($Process.HasExited) {
        return
    }

    Stop-Process `
        -Id $Process.Id `
        -Force `
        -ErrorAction SilentlyContinue
}

Write-Host '[1/4] 先尝试固定 Tailscale ADB 端口 5555……'

$Fixed = Connect-And-Probe -Target $FixedTarget

if ($Fixed.Healthy) {
    Write-Host "已经恢复：$FixedTarget"
    exit 0
}

Write-Host "固定端口尚不可用：$($Fixed.ConnectText)"
Write-Host '[2/4] 读取无线调试 TLS 端口；读取不到时才从 30000 开始并行扫描。'
Write-Host 'SSH 会提示密码，请输入 0526。'

$RemoteScript = @'
#!/data/data/com.termux/files/usr/bin/bash
set +e

p="$(getprop service.adb.tls.port 2>/dev/null | tr -d '\r\n')"

if [[ "$p" =~ ^[0-9]+$ ]] && (( p >= __START__ && p <= __END__ )); then
    printf 'PROP:%s\n' "$p"
    exit 0
fi

probe_port() {
    local port="$1"

    timeout 0.20 bash -c \
        "exec 3<>/dev/tcp/127.0.0.1/$port" \
        >/dev/null 2>&1

    if [ "$?" -eq 0 ]; then
        printf 'OPEN:%s\n' "$port"
    fi
}

export -f probe_port

seq __START__ __END__ |
    xargs -P __PARALLEL__ -n 1 \
        bash -c 'probe_port "$1"' _
'@

$RemoteScript = $RemoteScript.Replace(
    '__START__',
    [string]$StartPort
)

$RemoteScript = $RemoteScript.Replace(
    '__END__',
    [string]$EndPort
)

$RemoteScript = $RemoteScript.Replace(
    '__PARALLEL__',
    [string]$Parallel
)

$SshArguments = @(
    '-T',
    '-o',
    'ConnectTimeout=10',
    '-o',
    'ConnectionAttempts=1',
    '-p',
    [string]$SshPort,
    $SshTarget,
    'bash -s'
)

$DiscoveryOutput = @(
    $RemoteScript |
        & $SshExe @SshArguments 2>&1
)

$DiscoveryExitCode = $LASTEXITCODE

$CandidatePorts = [System.Collections.Generic.List[int]]::new()

foreach ($Line in $DiscoveryOutput) {
    $Text = ([string]$Line).Trim()

    if ($Text -match '^(?:PROP|OPEN):([0-9]+)$') {
        $Port = [int]$Matches[1]

        if (
            $Port -ge $StartPort -and
            $Port -le $EndPort -and
            -not $CandidatePorts.Contains($Port)
        ) {
            $CandidatePorts.Add($Port)
        }
    }
}

if ($CandidatePorts.Count -eq 0) {
    Write-Host "没有找到候选端口。SSH ExitCode=$DiscoveryExitCode"

    foreach ($Line in $DiscoveryOutput) {
        Write-Host "SSH> $Line"
    }

    exit 2
}

Write-Host (
    '候选端口：' +
    (($CandidatePorts | Sort-Object) -join ', ')
)

Write-Host '[3/4] 逐个验证是否真的是已配对的无线 ADB。'

$CandidateIndex = 0

foreach ($Port in ($CandidatePorts | Sort-Object)) {
    $CandidateIndex++
    $WorkingTarget = $null
    $TunnelProcess = $null

    $DirectTarget = "${PhoneIp}:$Port"

    Write-Host "尝试 Tailscale 直连：$DirectTarget"

    $Direct = Connect-And-Probe -Target $DirectTarget

    if ($Direct.Healthy) {
        $WorkingTarget = $DirectTarget
        Write-Host "无线 ADB 直连成功：$DirectTarget"
    }

    if (-not $Direct.Healthy) {
        Write-Host (
            "直连未通过真实 shell 验证：" +
            $Direct.ConnectText
        )

        $LocalPort = 37150 + $CandidateIndex

        while (
            Test-TcpPort `
                -Address '127.0.0.1' `
                -Port $LocalPort `
                -TimeoutMs 150
        ) {
            $LocalPort++
        }

        $ForwardSpec = (
            "127.0.0.1:${LocalPort}:" +
            "127.0.0.1:$Port"
        )

        Write-Host ''
        Write-Host (
            "将弹出一个 SSH 窗口建立临时转发。输入密码 0526，" +
            '认证后窗口会保持空白，这是正常的。'
        )

        $TunnelArguments = @(
            '-N',
            '-o',
            'ExitOnForwardFailure=yes',
            '-o',
            'ConnectTimeout=10',
            '-o',
            'ServerAliveInterval=5',
            '-o',
            'ServerAliveCountMax=2',
            '-L',
            $ForwardSpec,
            '-p',
            [string]$SshPort,
            $SshTarget
        )

        $TunnelProcess = Start-Process `
            -FilePath $SshExe `
            -ArgumentList $TunnelArguments `
            -PassThru

        $Deadline = (Get-Date).AddSeconds(35)
        $TunnelReady = $false

        while ((Get-Date) -lt $Deadline) {
            if ($TunnelProcess.HasExited) {
                break
            }

            $TunnelReady = Test-TcpPort `
                -Address '127.0.0.1' `
                -Port $LocalPort `
                -TimeoutMs 300

            if ($TunnelReady) {
                break
            }

            Start-Sleep -Milliseconds 500
        }

        if ($TunnelReady) {
            $TunnelTarget = "127.0.0.1:$LocalPort"
            $TunnelAdb = Connect-And-Probe -Target $TunnelTarget

            if ($TunnelAdb.Healthy) {
                $WorkingTarget = $TunnelTarget
                Write-Host "SSH 转发无线 ADB 成功：$TunnelTarget"
            }
        }
    }

    if ($null -eq $WorkingTarget) {
        Stop-TunnelProcess -Process $TunnelProcess
        continue
    }

    Write-Host '[4/4] 已进入无线 ADB，切换回固定 5555……'

    $Tcpip = Invoke-External `
        -FilePath $Adb `
        -Arguments @(
            '-s',
            $WorkingTarget,
            'tcpip',
            '5555'
        ) `
        -TimeoutMs 12000

    $TcpipText = (
        (
            $Tcpip.StdOut +
            ' ' +
            $Tcpip.StdErr
        ) -replace '\s+', ' '
    ).Trim()

    if ($TcpipText) {
        Write-Host "adb tcpip 输出：$TcpipText"
    }

    Start-Sleep -Seconds 4

    $FixedPortOpen = Test-TcpPort `
        -Address $PhoneIp `
        -Port 5555 `
        -TimeoutMs 6000

    if ($FixedPortOpen) {
        $Recovered = Connect-And-Probe -Target $FixedTarget

        if ($Recovered.Healthy) {
            Write-Host ''
            Write-Host "恢复成功：$FixedTarget"
            Write-Host '真实 adb shell 验证通过。'

            Stop-TunnelProcess -Process $TunnelProcess
            exit 0
        }
    }

    Write-Host '已执行 adb tcpip 5555，但固定端口仍未通过验证。'

    Stop-TunnelProcess -Process $TunnelProcess
}

Write-Host ''
Write-Host '找到候选端口，但没有成功恢复固定 5555。'
exit 3
