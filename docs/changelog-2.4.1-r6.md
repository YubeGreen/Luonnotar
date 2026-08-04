# 努昂诺塔 2.4.1 r6

- versionCode: 68
- 特权引擎 revision: 246
- 状态 schema: 12
- 配置 schema: 6

## 基于 r245 实测的修复

### 平板：WhatsApp 即时投递保护窗

r245 已能识别 `UID_FROZEN_WAKELOCK`、`XIAOMI_GREEZER_DENIAL` 与
`GCM_DELIVERY_CANCELLED`，但 8 秒短暂唤醒结束后 WhatsApp 会在下一次投递前后再次被冻结。
r6 将熔断期的事后救援改为事件驱动的短期保护租约：

- 真实 C2DM/GCM 投递失败出现时立即解冻 WhatsApp 与 GMS；
- 熄屏时把 WhatsApp 启动到前台并保持，不再 8 秒后立即返回桌面；
- 基础保护 45 秒；新的独立投递事件会延长 30 秒；总时长最多 90 秒；
- 保护期内每秒验证冻结状态，每 10 秒重放后台策略，每 15 秒执行 GMS Binder pulse；
- 屏幕亮起或保护期结束时自动返回桌面；
- 第二个独立投递事件仍遇到真实冻结时，每个保护窗最多执行一次 `am kill -> package unstop -> 重新启动`；
- 不对 WhatsApp 自动执行 force-stop。

### iQOO：GMS 恢复后的防复冻稳定租约

r245 的一次恢复曾成功建立 5228，但约 11 秒后新 GMS 进程再次被 vivo fast_freezer 冻结。
r6 不再把“5228 刚出现”当作接近成功，而是在恢复后进入 90 秒保护阶段：

- 通过公开 GoogleApiClient / Location Settings API 持续产生真实 Binder 交互；
- 每 2 秒建立一次短连接并执行只读查询；
- 短诊断 pulse 正在运行时，稳定租约会主动接管，而不会因 `already_running` 被拒绝；
- 5228 与 GMS 未冻结必须连续稳定 60 秒才判定战役成功；
- 稳定期内发生短暂复冻后先给 20 秒非破坏性恢复窗口，再决定是否 reset。

### GMS 自适应重试与独立破坏性预算

固定 30 分钟冷却会把已经确认的 GMS outage 固化。r6 改为按连续失败次数退避：

- 第一次失败：2 分钟；
- 第二次失败：5 分钟；
- 第三次失败：15 分钟；
- 后续失败：30 分钟；
- 自动恢复仍必须同时具备真实冻结与 MCS 缺失等强证据。

force-stop 与普通恢复战役分开计费：

- 同一战役内 vivo/iQOO 最多 1 次 force-stop；
- 两次 GMS force-stop 至少间隔 10 分钟；
- 24 小时最多 6 次；
- 单次 force-stop 命令只尝试一次；
- 超出破坏性预算时仍允许 stop-app、unfreeze、Binder 租约和策略重放，不会整条恢复链停摆。

### 既有目标保持不变

- Signal `org.thoughtcrime.securesms` 继续作为普通进程与包策略目标；
- Signal 不进入 WhatsApp 专用投递保护或破坏性恢复路径；
- r245 的 Termux、Tailscale 与本机 5555 活性保护继续保留。
