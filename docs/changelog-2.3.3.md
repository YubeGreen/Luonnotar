# 努昂诺塔 2.3.3

版本：`2.3.3` / `versionCode 55`

## 厂商冻结探测与验证式恢复

本版针对两类实机故障补齐 Shell UID 2000 守护盲区：

- OriginOS / iQOO 的标准 `am_app_frozen ... from fast_freezer`；
- HyperOS 的 `Greezer Denial`、`reason: UidFrozen`、C2DM 广播被拒绝以及 GCM 回调 `result=CANCELLED`。

旧实现只读取 `events` buffer 中的 `am_app_frozen`。HyperOS 的 Greezer 证据主要位于 `system` / `main` buffer，因此引擎虽然存活，却无法看到 WhatsApp 已被冻结，更无法在 FCM 到达时干预。

## 新行为

- 事件观察器改为同时读取 `events`、`system` 和 `main`，并使用严格 tag 过滤，避免全量 logcat 带来的无关开销。
- 只解析配置中的受保护目标；其他应用的 Greezer 日志会被忽略。
- 命中后按目标包执行定向恢复，不再仅依赖下一次 15 秒轮询：
  - 立即、1 秒、3 秒、10 秒、30 秒进行一组 sticky unfreeze 重申；
  - 普通冻结证据建立 5 分钟保持窗口；
  - C2DM / FCM 投递拒绝建立 15 分钟保持窗口；
  - 保持窗口内，常规 15 秒周期会持续重申解冻。
- FCM 投递失败时同时解冻目标应用和 GMS，但不会因此直接 `force-stop` GMS。
- 若标准 cgroup 冻结状态可见，每轮都会验证解冻后状态；若厂商冻结层不可见，会明确记录 `state_unobservable`，不再把命令返回成功误报为已恢复。
- 若一次恢复后 15 秒内再次出现冻结证据，会记录 `vendor_freeze_relapse`。
- 引擎状态新增：厂商冻结信号数、投递拒绝数、恢复轮次、按包保持窗口与复发次数；主界面摘要同步显示前三项。
- 在界面切换“自动 GMS 深度恢复”后，会立即把新配置同步到正在运行的内置 Shell 引擎，不再要求重启引擎后才生效。

## GMS 深度恢复边界

原有自动 GMS 深度恢复策略保持不变：只有 GMS 自身出现足够的标准冻结证据、用户显式开启自动恢复、且冷却与每日上限允许时，才会执行 `am force-stop com.google.android.gms`。WhatsApp 的 Greezer 投递拒绝本身不会触发炸 GMS。

## 重要限制

- 已被系统取消的单次 C2DM payload 无法由努昂诺塔重新构造或重放；本版通过及时解冻并保持目标进程，争取 GMS/WhatsApp 后续重试成功。
- `am unfreeze --sticky` 是否能约束厂商私有冻结器仍取决于 ROM。本版会记录复发而不是假装成功，最终效果必须以熄屏实机消息测试为准。
- 本版未修改普通守护服务、VPN 探测、通知到达记录、液态玻璃界面和开机自启动策略。
