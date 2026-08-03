# 努昂诺塔 2.4.1

版本：`2.4.1` / `versionCode 63`  
特权引擎：`revision 241`

## iQOO / vivo GMS 恢复闭环

2.4.0 在 iQOO 实机上能够确认 GMS main / persistent 被 `fast_freezer` 冻结且 MCS 端口持续缺失，但优先执行的 `am stop-app` 只替换了 PID；继任进程数秒内再次冻结，最终恢复活动撞上每日活动上限。

2.4.1 将已经由实机手动验证成功的序列正式写入自动恢复：

1. vivo / iQOO 首次重置即直接走 `am force-stop --user 0 com.google.android.gms`；
2. 等待旧 PID 消失，并保留 2 秒 stopped-state 沉降窗口；
3. 使用运行时预检通过的 `cmd package unstop --user 0 com.google.android.gms`，失败时最多重试三次；
4. 短暂等待后发送 GMS Binder pulse，唤醒依赖进程并重新应用后台策略；
5. 只有 GMS 不再冻结且 5228/5229/5230 传输连续健康 15 秒才宣布成功。

非 vivo 设备仍可先尝试 `stop-app`，但同一活动中出现继任复冻或进入第二次重置后会自动升级到 `force-stop → unstop`。

## 不再永久停摆

- 单次 GMS 活动延长到 3 分钟，最多允许 8 次有间隔的重置。
- vivo / iQOO 在强证据故障下使用 30 秒活动冷却。
- 正常每日活动预算耗尽后，不再把真实故障封死 24 小时，而是切换为 10 分钟退避重试。
- 用户手动恢复在每日预算耗尽后仍可执行。

## 验收标准

- 日志必须出现 `gms_recovery_force_stop_started` 与 `gms_recovery_force_stop_unstop_verified`。
- 最终必须出现 `gms_recovery_campaign_finished ... success=true`。
- 最终 GMS main / persistent 均不得为 `cgroup2.freeze:1`。
- `ss -H -tn` 必须观察到 5228、5229 或 5230 的 ESTABLISHED 连接持续至少 15 秒。
- 覆盖安装后必须重启 Shell 特权引擎，旧 revision 240 不会载入本次恢复策略。
