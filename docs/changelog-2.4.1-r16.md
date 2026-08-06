# Luonnotar 2.4.1 r16（privileged engine revision 256）

r256 不再让 Kotlin 线程先接收冻结日志、扫描进程与 cgroup，再启动解冻命令。GMS 的第一响应下沉到特权引擎中的常驻 Shell 快速通道；Kotlin 只负责策略、统计、transport 复核与降级恢复。

## Shell-resident GMS freezer fast lane

- `/system/bin/sh` 子进程直接拥有筛选后的 `logcat` 管道。
- 同时识别 `com.google.android.gms` 与 `com.google.android.gms.persistent`；命中 `am_app_frozen` 后，Shell 在转发原始日志前先解冻实际命中的进程。
- main 与 persistent 使用独立的 100 ms 首击去抖，避免两条紧邻事件互相吞掉。
- 优先执行 `cmd activity unfreeze [--sticky] <process>`；失败时在同一次动作中退回 `am unfreeze [--sticky] <process> --user 0`。
- 即使系统没有外部 `timeout` 命令，也会使用内置 watchdog 将单条命令限制在约 1 秒内。
- Shell 由 app_process / UserService PID 监护；父进程消失、服务停止或日志管道结束时清理 FIFO、worker 与私有临时目录。
- 3 秒内未收到 `READY`、Shell 启动失败或运行中退出时，自动切回旧 Kotlin logcat watcher。
- 配置热重载会核对 sticky 设置和 GMS 是否仍在目标集合中；不兼容时重启 watcher，而不是继续使用旧参数。

## 有界 thaw shield

- 每个冻结 episode 建立 45 秒软租约；连续复冻可续期，但从该 episode 首个信号起最多 120 秒。
- 软租约已结束后出现的新信号会建立新 episode，并重置 24 次首击额度；不会错误继承旧 episode 的硬截止时间。
- cgroup 状态可见时，仅在确认 `FROZEN` / `FREEZING` 后执行解冻；前 3 秒每 100 ms、3–10 秒每 250 ms、之后每秒检查。
- cgroup 状态不可见时，只在 0 / 1 / 3 / 7 秒进行盲重申，不持续轰炸 ActivityManager。
- main 与 persistent 的每次实际目标调用分别计数；单 episode 最多 48 次 shield 目标调用，不再把一次双目标批次误算成一次。
- 探测结果会在状态变化时再次上报，因此可观察 `frozen/absent/unobservable → thawed`，而不是只保留第一次结果。
- 当部分 GMS PID 可见、部分不可见时，整体状态保持 `unobservable`；不会因为一个可见 PID 已 thawed 就误判全部恢复。
- 新冻结信号并入当前有效 episode，不启动多组互相追尾的 worker。

## Kotlin 策略层调整

- 快速通道健康时，GMS 的 AOSP freeze 原始日志不再同时触发旧 vendor burst。
- `SIGNAL` / `FIRST` 协议携带实际进程名，日志可以区分 main 与 persistent。
- 恢复状态区分：命令接受、可见 cgroup 解冻、不可见的临时恢复、shield 完成及 transport 后续恢复。
- 不可见但命令已接受时可先进行一次受限的 MCS/anchor 后处理；若最终仍 frozen、absent、unknown 或额度耗尽，再交回 Kotlin fast-thaw。
- 同一 sequence 的后处理与“已验证解冻”分别去重：先不可见、后可见 thawed 时，仍会补记真实成功，但不会重复发起后处理。
- 后处理调度失败会回滚 cooldown 与计数，避免一次 executor 拒绝永久压住后续恢复。
- 普通 Kotlin 解冻路径同样优先 direct ActivityManager，失败后自动退回 `am`。
- 状态 JSON 的 `gmsFreezerFastLane` 新增 `stickyConfigured`、`targetEnabled` 及完整首击/shield 计数。

## root cgroup 写入收口

- `rootCgroupThaw` 默认关闭，配置 schema 升至 7。
- 旧 schema 即使保存过 `true` 也迁移为关闭。
- 只有实际 UID 0 才允许直接写 freezer 控制文件；普通 Shell / Shizuku UID 不再反复制造已知 `EACCES`。

## AOSP / 厂商 freezer 归因实验

`tools/test-iqoo-sticky-unfreeze.ps1` 的 `GlobalFreezerOff` 改成两阶段流程：

```powershell
# 第一次：保存原始 override，写入 use_freezer=false
.\tools\test-iqoo-sticky-unfreeze.ps1 `
  -Mode GlobalFreezerOff `
  -DeviceSerial "<serial>"

# 重启设备后再次执行同一命令；脚本会验证 boot_id 已改变，再开始采集
.\tools\test-iqoo-sticky-unfreeze.ps1 `
  -Mode GlobalFreezerOff `
  -DeviceSerial "<serial>"

# 测试结束：精确恢复原始 override，然后再次重启
.\tools\test-iqoo-sticky-unfreeze.ps1 `
  -RestoreGlobalFreezer `
  -DeviceSerial "<serial>"
```

状态文件按设备保存在 `tools/test-output/global-freezer-state-<serial>.json`；恢复时也可显式传入 `-RestoreStateFile`。脚本不会把“仅写入 native_boot 配置、尚未重启”误当作有效 A/B 窗口。

关闭 AOSP freezer 后仍出现 `fast_freezer` / QuickFrozen / PEM 复冻，说明厂商层仍参与；彻底消失说明 AOSP 层至少是必要条件；仅改善则更像两层叠加。该实验只能做归因，不能证明 ROM 私有 freezer 已被关闭。

## 版本与 schema

- versionName: 2.4.1
- versionCode: 78
- privileged engine revision: 256
- config schema: 7
- status schema: 17

## 二次审计验证

已完成：

- Kotlin 核心与完整 `PrivilegedGuardianUserService` 在 Android/API stub 下编译通过。
- Shell 生成器经 `kotlinc` 生成实际脚本，并通过 `sh -n`。
- 伪造 `logcat/cmd/am/pidof` 的端到端测试确认：persistent 与 main 在 50 ms 内连续出现时都执行首击，且每条 `FIRST` 都先于对应原始日志转发。
- direct 命令失败时，确认会退回带 `--user 0` 的 `am`；direct `cmd activity` 本身不附加该参数。
- 强制隐藏外部 `timeout` 后，内置 watchdog 能终止挂起命令并继续 fallback。
- 协议解析、命令构造、terminal-state policy 与现有 Python 工具测试通过。

当前环境没有可用的 Android SDK 与 Gradle distribution 缓存，因此这里仍未完成正式 `assembleRelease` / Android instrumented test。iQOO 与小米 ROM 的真实复冻抑制效果也必须用真机 A/B 验证，源码检查不能替代这一项。
