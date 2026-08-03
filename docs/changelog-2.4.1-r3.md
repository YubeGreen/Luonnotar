# 努昂诺塔 2.4.1 r3（engine revision 243）

## 现场根因

- Pad 的 Tailscale VPN、Wi-Fi 与 GMS MCS 连接均仍健康；断送达不是 VPN 断线。
- revision 242 在继任 WhatsApp 再冻结后升级到 `am stop-app`，旧 PID 被移除，但系统没有自动创建继任进程；守护随后只等待进程出现，形成“守护自己把 WhatsApp 停在无进程状态”的死路。
- Pad 上的 Termux 已处于 `stopped=true` 且无进程；revision 242 的默认目标并未包含 Termux / Termux:Boot。

## 修复

- versionCode 65；特权引擎 revision 243；状态 schema 9；引擎配置 schema 5。
- 不再把 `STOP_APP` 作为计划中的继任重置阶段：前四次继续使用 `am kill`，Xiaomi/vivo 从第 5 次开始使用经过能力验证的 `force-stop -> package unstop`。
- 若 WhatsApp 已无进程，恢复请求不再直接跳过：先解除 stopped 状态、重放后台策略、脉冲 GMS，再启动 24 小时继任守护。
- 继任守护新增无进程恢复：10 秒策略脉冲；持续 15 秒仍无继任进程时，仅在熄屏状态后台启动应用入口并立即返回桌面；每分钟重试，实际启动/失败尝试最多 12 次。亮屏时延后而不消耗尝试额度。
- 默认进程目标加入 `com.termux`；默认包目标加入 `com.termux` 与 `com.termux.boot`。旧 schema 4 配置自动迁移，显式 schema 5 自定义目标保持不变。
- 每轮后台策略会执行并验证 `cmd package unstop`，防止厂商清理后包长期停留在 `stopped=true`。
- Termux 无进程时每 5 分钟执行一次受限恢复：解除 stopped、重放策略，并在熄屏时尝试后台启动入口。
- Termux:Boot 仅作为开机广播/后台策略目标；它通常是短生命周期组件，不按常驻进程重建。未安装时明确跳过。

## 验收重点

- Pad 日志应出现 `package_successor_absent`、`package_successor_absence_pulse`，必要时出现 `package_background_launch_succeeded` 与 `package_successor_returned`。
- 不应再出现 revision 242 那种 `am stop-app` 后长期无 WhatsApp PID、守护只等待的死路。
- 已安装 Termux 的设备应在 `background_policy_target` 中看到 `com.termux`；安装 Termux:Boot 的设备还应看到 `com.termux.boot`。
