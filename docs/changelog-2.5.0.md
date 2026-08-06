# 努昂诺塔 2.5.0（engine revision 257）

版本：`2.5.0` / `versionCode 79`
特权引擎：`r257`
状态结构：`schema 18`

## 这次解决的不是“反应太慢”

iQOO / OriginOS 实测已经证明：即使 Android Cached Apps Freezer 的有效状态为
`use_freezer=false`、`Apps frozen: 0`，vivo PEM / fast_freezer 仍能直接把
`com.google.android.gms` 与 `com.google.android.gms.persistent` 写入冻结 cgroup。
因此 r256 依赖 `am_app_frozen` 和普通 `cmd activity unfreeze` 的方案只能抢到短暂
送达窗口，无法稳定维持 MCS 5228。

2.5.0 的核心判断是：厂商层改变了真实 cgroup 状态，却没有同步 Android
`system_server` 内的 `ProcessCachedOptimizerRecord`。普通 unfreeze 命令可能返回 0，
但 framework 因为认为进程并未冻结而不执行物理解冻。

## 唯一主路线：Adopt → Release

新增 `GmsVendorFreezeBridge`，仅在 vivo / iQOO 设备启用：

1. Shell 直接缓存并轮询 GMS main / persistent 的 `cgroup.freeze`；
2. 先执行一次普通、非 sticky 的 framework unfreeze；
3. 若 cgroup 仍为 1，执行一次强制 framework `freeze`（不把“冻结”设为 sticky），
   让 system_server 接管该 ProcessRecord 的 binder/cgroup freezer 状态；
4. 短暂等待后执行非 sticky unfreeze，让 system_server 从一致的内部状态释放 binder
   与 cgroup；
5. 验证真实 cgroup 已为 0 后，再写入 sticky-unfrozen 决策，阻止 AOSP 自己重新冻结；
6. 三次失败即判定 vendor refreeze lock，进入 15 秒冷却并立即交给现有恢复层，避免
   无休止命令对轰。

该路径不直接写 cgroup 文件，不需要 root，也不依赖尚未发现的 vivo 私有白名单 API。

## 独占恢复与自修复

- vivo / iQOO 上停用 r256 的主动 logcat fast lane，改用 legacy logcat 只收证据；即时
  恢复由 cgroup bridge 独占，普通 60 秒轮询也不再对 GMS 发 freezer 命令，避免三条路径
  同时拆散 Adopt → Release 事务。
- bridge 空闲时每 1 秒检查；冻结风暴期间每 150 ms 检查。
- PID 与 cgroup 路径缓存，进程重启后自动重新绑定。
- 每 5 秒发送 bridge 心跳；15 秒无心跳则特权引擎重启 bridge。
- 特权状态加入 `snapshotElapsed`。响应 socket 但超过 45 秒未发布新状态，也会被视为
  僵死实例。
- `:keeper` 每 30 秒向默认 App 进程发送显式监督广播；receiver 使用异步广播生命期，
  所有状态读取与修复仍只在默认进程进行，避免跨进程 SharedPreferences runtime owner
  相互覆盖。UI 从未打开也能触发自动修复。
- app_process 实例心跳由 60 秒缩短为 10 秒；状态 socket 仍响应但快照超过 45 秒时，
  默认进程会先请求销毁并确认旧实例真正停止，再启动新的 setup generation；未停止时
  不并发拉起第二个实例。

## 诊断字段

`gmsVendorFreezeBridge` 新增：

- `targetEnabled` / `vendorFamily`
- `alive` / `ready` / `heartbeatAgeMs`
- `frozenCount`
- `plainRecoveryCount`
- `adoptReleaseCount`
- `verifiedRecoveryCount` / `failedRecoveryCount`
- `vendorLockCount`
- main / persistent PID 与真实 cgroup 状态

## 风险边界

Adopt → Release 会短暂要求 system_server 正式冻结目标 ProcessRecord。Android freezer
会同时处理 binder；如果期间存在不允许的同步 binder 事务，framework 可能杀掉该 GMS
进程。2.5.0 因此只在 vivo / iQOO 启用，并设置严格重试上限和冷却。GMS 进程重建属于
已有恢复模型可处理的情况，但仍需真机确认它在当前 OriginOS 版本上的实际收益。

## 当前验证

已完成：

- 新协议与纯 Kotlin 编译；
- 生成 Shell 的 `bash -n`；
- Adopt → Release 命令顺序、目标名称、非 root cgroup 只读约束检查；
- 状态新鲜度策略测试；
- r256 pipe hotfix 基线差异审计。

尚未完成：

- Android Studio 完整 Gradle / Lint / Release 构建；
- iQOO 真机验证 Adopt → Release 是否能把 `cgroup.freeze` 稳定释放；
- 20 分钟 WhatsApp / MCS 5228 对照测试。
