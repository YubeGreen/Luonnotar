# Luonnotar ADB 工具

## v130 远程控制面入口

Mac 端推荐统一使用项目安装的 `luoterm` 快捷入口。它必须显式选择设备；iQOO 快捷方式为：

```bash
luoterm --iq
```

也支持：

```bash
luoterm HOST
luoterm HOST:PORT
luoterm USER@HOST:PORT
```

若 Termux `:8022` 不可达，脚本先尝试固定 ADB `:5555` 的 `rescue_termux_sshd`，再回退到努昂诺塔独立 SSH `:8025`；若 adbd restart 正好让 `:8025` 短暂消失，则等待 keeper 重生后再主动发一次恢复。默认等待 120 秒。

手机端三条控制面职责：

- `:8025`：努昂诺塔 shell rescue SSH；由 keeper 在 shell engine / adbd restart 后重新建立。
- `:5555`：固定远程 ADB；guardian 从 Binder 取得当前 Wireless ADB 端口后由 app-side Kadb 重发 `tcpip:5555`。
- `:8022`：Termux sshd；guardian 同时要求 sshd PID 与端口 listener 存在，失联后通过 Termux `RUN_COMMAND` 重启。

主线状态文件 `/data/local/tmp/luonnotar-guardian-status.json` 中的 `adbTcp5555.phase` 与 `termuxSshd.phase` 可直接区分 grace、恢复到期和 cooldown/backoff。

## 锁屏状态修改守护配置

1.7.14 继续使用 `android.permission.DUMP` 保护的同步 ContentProvider 入口。
手机保持锁屏、无需打开努昂诺塔界面；该路径不经过广播队列。

查看当前配置和运行态：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File "H:\Android\Luonnotar\tools\set-adb-runtime-config.ps1" `
  -DeviceSerial "127.0.0.1:5132" `
  -StatusOnly
```

### 旧的单次探测预设

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File "H:\Android\Luonnotar\tools\set-adb-runtime-config.ps1" `
  -DeviceSerial "127.0.0.1:5132" `
  -OriginOsPreventionPreset
```

该预设修改：

- `high_perf_wifi_lock=true`
- `periodic_dns=true`
- `periodic_https=true`

### 1.7.14 持续租约实验预设

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File "H:\Android\Luonnotar\tools\set-adb-runtime-config.ps1" `
  -DeviceSerial "127.0.0.1:5132" `
  -OriginOsPersistentLeasePreset
```

该预设用于新的 OriginOS A/B 实验：

- `screen_off_cpu_guard=true`
- `high_perf_wifi_lock=true`
- `persistent_network_lease=true`
- `persistent_heartbeat_socket=true`
- `periodic_dns=false`
- `periodic_https=false`
- `automatic_mtalk=false`

持续 socket 是努昂诺塔自有的 TCP 连接，不模拟 GMS，也不实现 MCS 协议。
它只用于验证持续持有网络状态能否改变 OriginOS 的后台调度行为。

关闭持续租约实验：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File "H:\Android\Luonnotar\tools\set-adb-runtime-config.ps1" `
  -DeviceSerial "127.0.0.1:5132" `
  -DisableOriginOsPersistentLeasePreset
```

### 立即执行探测

无需等待 quiet window 或周期调度：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File "H:\Android\Luonnotar\tools\set-adb-runtime-config.ps1" `
  -DeviceSerial "127.0.0.1:5132" `
  -ProbeNow MTALK
```

`ProbeNow` 可选：`DNS`、`HTTPS`、`MTALK`、`ALL`。它不能与配置参数或
`StatusOnly` 同时使用。

配置写入后，现有 `:keeper` 守护服务会在进程内重载。服务未运行时，配置仍会成功
保存并返回 `service_reload_deferred=true`，等待下次启动应用。

## PUSH_TEST ADB 恢复观察器

`watch-push-test-recovery.ps1` 是电脑端实验伴侣。它读取 WhatsApp 自动发送器的
`send-events.csv`，并同时核对 APK 的实时回调水位、activeNotifications 补扫
水位和监听器健康状态。

关键规则：

- 默认仅观察；必须显式传入 `-EnableRecovery` 才允许 force-stop GMS。
- 每轮短暂打开 CSV，使用 `FileShare.ReadWrite | FileShare.Delete`，不长期锁住共享文件。
- 忽略 UTF-8 BOM、半写入尾行、原子替换，并默认回溯最近五分钟发送历史。
- 最近五分钟内存在持久化实时到达时，可恢复观察器重启前的 armed 状态。
- 标题、联系人名和群名不能成为送达证据；普通聊天正文不会写入日志。
- 补扫只提供到达时间上界，不会把监听器重连补扫伪装成实时回调，也不会武装恢复。
- 恢复前必须确认隐私、监听器持久化/运行时连接和心跳健康，并进行两次主动补扫。
- 同一积压事件默认最多恢复两次；日志使用 `main/system/crash/radio` 缓冲区。

先做观察验收：

```powershell
powershell -ExecutionPolicy Bypass `
  -File "H:\Android\Luonnotar\tools\watch-push-test-recovery.ps1" `
  -SendEventsPath "C:\SMB-Probe\send-events.csv" `
  -DeviceSerial "127.0.0.1:5132" `
  -DeliveryTimeoutSeconds 60 `
  -RecoveryCooldownSeconds 180 `
  -MaxRecoveriesPerIncident 1 `
  -ObserveOnly
```

输出目录默认位于桌面 `Luonnotar-Push-Recovery`，包含观察 CSV、实时 logcat、
恢复前证据和 logcat 标准错误。



## 1.7.16 单变量预设

持续 VPN 租约组：

```powershell
.\set-adb-runtime-config.ps1 `
  -DeviceSerial "127.0.0.1:5132" `
  -OriginOsPersistentLeasePreset
```

持续 HTTPS 心跳组：

```powershell
.\set-adb-runtime-config.ps1 `
  -DeviceSerial "127.0.0.1:5132" `
  -OriginOsHeartbeatSocketPreset
```

兼容旧命令：`-PersistentMtalkSocket` 仍可作为
`-PersistentHeartbeatSocket` 的参数别名。


## 1.7.17 实验会话与自动分析

1.7.17 不新增保活干预。它只把每轮测试的边界和人工事件写成结构化证据，
避免后续依赖聊天记录或手工对时。

开始会话：

```powershell
.\set-adb-experiment-session.ps1 `
  -DeviceSerial "100.111.89.64:5555" `
  -Start "normal_use_whatsapp_background"
```

写入标记：

```powershell
.\set-adb-experiment-session.ps1 `
  -DeviceSerial "100.111.89.64:5555" `
  -Mark "remote_unlock"
```

结束会话：

```powershell
.\set-adb-experiment-session.ps1 `
  -DeviceSerial "100.111.89.64:5555" `
  -Stop
```

查看状态：

```powershell
.\set-adb-experiment-session.ps1 `
  -DeviceSerial "100.111.89.64:5555" `
  -StatusOnly
```

会话名和标记会过滤换行、分号和等号，以保证 ContentProvider 的同步 wire
格式不会被用户输入破坏。开始会话时会记录当前 profile、实验开关、屏幕、
Device Idle、CPU/Wi-Fi Lock、VPN handle 与 validated 状态。

分析单个诊断包：

```powershell
.\analyze-luonnotar-push-session.ps1 `
  -InputPath "C:\Users\ysbss\Desktop\iqoo-test.zip"
```

同时比较多台设备：

```powershell
.\analyze-luonnotar-push-session.ps1 `
  -InputPath @(
    "C:\Tests\iqoo.zip",
    "C:\Tests\xiaomi-a.zip",
    "C:\Tests\xiaomi-b.zip"
  ) `
  -SourceName @("iQOO", "Xiaomi-A", "Xiaomi-B") `
  -SendEventsCsv "C:\SMB-Probe\send-events.csv"
```

输出包括 `summary.md`、`whatsapp-outages.csv`、`push-deliveries.csv`、
`backlog-releases.csv`、`missing-arrival-evidence.csv`、
`cross-device-sequences.csv` 和 `experiment-events.csv`。

## r256：AOSP freezer 与厂商 freezer 的隔离测试

不要在写入 `activity_manager_native_boot/use_freezer=false` 后直接开始测试；该项需要重启后才进入有效比较窗口。

```powershell
# 1. 保存原始值并准备关闭 AOSP cached-app freezer
.\test-iqoo-sticky-unfreeze.ps1 `
  -Mode GlobalFreezerOff `
  -DeviceSerial "100.111.89.64:5555"

# 2. 重启手机
adb -s "100.111.89.64:5555" reboot

# 3. 手机重新连上 ADB 后，再执行同一命令开始采集
.\test-iqoo-sticky-unfreeze.ps1 `
  -Mode GlobalFreezerOff `
  -DeviceSerial "100.111.89.64:5555"

# 4. 结束后恢复原来的 override，并再次重启
.\test-iqoo-sticky-unfreeze.ps1 `
  -RestoreGlobalFreezer `
  -DeviceSerial "100.111.89.64:5555"
adb -s "100.111.89.64:5555" reboot
```

脚本用设备 `boot_id` 阻止“未重启就采集”，并把测试前的 override 按设备保存到
`test-output/global-freezer-state-<serial>.json`。不要手工删除该文件，直到恢复完成。
