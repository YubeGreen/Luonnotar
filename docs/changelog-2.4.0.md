# 努昂诺塔 2.4.0

版本：`2.4.0` / `versionCode 62`  
特权引擎：`revision 240`

## 目标

2.4.0 不再把“命令返回成功”当成恢复完成。它针对 2026-08-03 的两类实机故障分别建立有界恢复状态机：

- HyperOS：WhatsApp 的 cgroup 被冻结，而 GMS/MCS 仍健康；一次进程重建只能短暂释放积压，继任进程会再次冻结。
- OriginOS：GMS 主进程与 persistent 进程同时被冻结，MCS 消失；一次 GMS 重建后继任进程也会再次冻结。

## WhatsApp 继任进程保护

- 新的 C2DM/GCM 投递失败事件只有在 cgroup 冻结被真实验证时才允许立即重建。
- 同一条投递产生的多种日志会合并为一个事件；关键重建最短间隔 15 秒，最多 48 次/24 小时。
- 先执行 `am kill`；旧 PID 仍存在且系统支持时，再降级到 `am stop-app`。不会自动 `force-stop` WhatsApp。
- 成功移除旧 PID 后进入两分钟继任进程保护窗口，每两秒检查一次。
- 继任进程再次冻结时，先尝试 sticky unfreeze；仍冻结则在单次保护窗口内最多重建四次。
- 只有继任进程连续 30 秒未冻结才记为稳定恢复；进程消失、持续复冻和达到限额都会明确记录。

## GMS 恢复战役

- 自动破坏性恢复需要同时具备：GMS cgroup 真实冻结、MCS 可观测且连续缺失。
- GMS 冻结期间把传输探测间隔压缩到最多 10 秒；连续三次缺失即可启动恢复战役，不依赖新 logcat 信号。
- 单次战役持续最多两分钟，每两秒验证 GMS 主进程、persistent 进程与 MCS。
- 优先使用 `am stop-app`；若旧 PID 未移除，只有在 `cmd package unstop` 已经通过运行时预检后才允许 `force-stop`，随后最多重试三次清除停止状态。
- 每次重置后触发 shell-only GMS Binder pulse、唤醒依赖进程并重新应用后台策略。
- 单次战役最多三次包级重置，间隔至少 10 秒；只有进程均未冻结且 MCS 连续健康 15 秒才算成功。
- 紧急战役最短间隔两分钟，最多 12 次/24 小时；不会清除 GMS 数据。

## 特权引擎启动

- mDNS 仍是首选连接方式。
- 已配对时，仅当 `service.adb.tcp.port` 精确为 `5555`，且 `127.0.0.1:5555` 短探测成功，才加入传统本机 ADB 端口候选。
- `persist.adb.tcp.port=5555` 单独出现会被视为可能陈旧，不会触发连接。
- 每个启动 generation 只执行一次本机 5555 安全探测，并记录原因。

## 诊断

状态 JSON 升级到 schema 7，新增：

- `packageSuccessorGuards`
- 继任进程复冻、重置与稳定计数
- `gmsRecoveryCampaign`
- GMS stop-app、force-stop、重置、复冻计数
- `supportsSecondaryProcessUnfreeze`、`supportsStopApp`、`supportsPackageUnstop`

液态玻璃 UI、`ui/visual` 与 `ui/motion` 未修改。
