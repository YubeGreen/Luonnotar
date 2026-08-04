# Luonnotar 2.4.1 r10（revision 250）

本次版本针对 OriginOS / iQOO 在 GMS Binder 持续连接和快速解冻均正常时，仍持续通过 `fast_freezer` 冻结 Google Play 服务的问题，引入有界的 GMS 进程重要性栅栏实验。

## GMS importance fence

- 在恢复租约期间，由前台 `:keeper` 进程对 GMS 发起显式服务绑定。
- 绑定使用 `BIND_AUTO_CREATE | BIND_IMPORTANT | BIND_ABOVE_CLIENT`；Android 10 及以上附加 `BIND_INCLUDE_CAPABILITIES`。
- 主槽位绑定公开的 Location GMS 服务入口。
- persistent 槽位按顺序尝试两个 Common GMS 服务入口；单个入口失败不会阻塞恢复战役。
- 绑定最长持续四分钟，重复恢复租约仅延长期限，不替换健康连接。
- Binder 死亡、服务断开或健康检查失败后 250 ms 有界重绑。
- 服务停止、守护关闭或恢复租约到期时完整解绑。

## 可观测性

- 新增 Shell 查询动作 `ADB_GMS_IMPORTANCE_FENCE_STATUS`。
- 特权引擎在恢复战役中每两秒采集栅栏连接状态。
- 记录 main / persistent 槽位的动作、实际组件与连接状态。
- 记录 GMS 两个进程在栅栏启用前后的 `oom_score_adj` 最低值、最高值和当前值。
- 记录 GMS UID process state，以及栅栏连接期间仍发生的冻结次数。
- 状态 schema 更新为 16。

## r249 收尾修复

- force-stop 门关闭后的 anchor-only 阶段不再输出 `Long.MAX_VALUE` 形成的巨大 `waitRemainingMs`。
- 快速解冻在每次命令前和命令后检查 1.5 秒截止线，并记录截止线超时次数及最大超时。

## 版本

- versionName: 2.4.1
- versionCode: 72
- privileged engine revision: 250
- config schema: 6
- status schema: 16
