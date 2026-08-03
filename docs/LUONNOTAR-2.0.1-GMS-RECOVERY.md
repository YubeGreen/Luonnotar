# Luonnotar 2.0.1：GMS 深度恢复

## 目标

2.0.1 把实机上已证明有恢复价值的 `am force-stop --user 0 com.google.android.gms`
纳入 shell/root UserService，但不把它当作普通心跳动作。

## 恢复链

1. 从 UserService 启动后的实时 `am_app_frozen` 流记录 GMS 冻结证据；不会回放整段历史 events buffer。
2. 普通周期继续执行 `am unfreeze [--sticky]` 与后台策略校准。
3. 自动模式仅在 10 分钟内出现至少 3 次相互间隔 5 秒以上的 GMS 冻结证据时升级。
4. 执行 `am force-stop --user 0 com.google.android.gms`。
5. 解冻 WhatsApp、WhatsApp Business 与 Tailscale 等依赖进程，促使其重新绑定 GMS。
6. 等待最多 45 秒，并要求旧 PID 全部消失且至少出现一个新 GMS PID，才记为 `restarted`。
7. 新进程出现后重新执行解冻和 GMS 策略校准。

## 防循环

- 自动恢复默认关闭，必须按设备单独开启。
- 自动恢复冷却 6 小时。
- 手动恢复冷却 2 分钟，避免双击。
- 任意恢复尝试每日最多 2 次。
- 命令返回 0 不算成功；必须观察到 PID 替换。

## Shizuku stale UserService 修复

绑定 15 秒超时后，2.0.1 会用 `remove=true` 删除旧 daemon UserService，等待 750ms，
自动重试一次。第二次仍失败才向 UI 报 `bind_timeout`。UserService tag 保持
`luonnotar.guardian.v2`，通过 versionCode 46 让 Shizuku 正确替换 2.0.0 的旧实例。

## 已知边界

`force-stop` 会让 Android 完整停止 GMS，并可能取消其任务与闹钟；因此它只作为最终恢复级。
若 45 秒内没有看到新 PID，状态会记为 `restart_not_observed`，不会伪报成功，也不会立即重试。
