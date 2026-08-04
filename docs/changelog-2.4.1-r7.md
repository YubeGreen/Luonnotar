# 努昂诺塔 2.4.1 r7

- versionCode：69
- 特权引擎 revision：247
- 配置 schema：6
- 状态 schema：13

## iQOO：把 Binder 租约前移到 MCS 建立之前

r246 只在观察到 5228 后启动稳定租约。实机日志证明，iQOO 的失败发生得更早：
GMS 新进程出现后从未建立 5228，随后被 OriginOS fast_freezer 冻结。因此 r247 在恢复战役开始时就启动应用侧 GoogleApiClient/Binder 租约，不再等待 MCS 已经健康。

- 战役启动即请求 120 秒 Binder 租约。
- GMS PID 发生替换时立即续租。
- 战役进行期间每 30 秒续租一次。
- 5228 建立后继续沿用同一租约完成 60 秒稳定验收。
- 应用侧重复请求不再只返回“已运行”；现在会延长活动租约，单次战役总保护时间最多 4 分钟。

## iQOO：恢复阶段改为等待，而不是快速耗尽重置次数

r246 在很短时间内执行 stop-app、force-stop、再次 stop-app。进程虽然被替换，但 GMS 没有获得足够时间完成 MCS 鉴权和连接。

r247 改为：

1. 预连接租约 + 持续解冻，先等待 20 秒。
2. 仍无 5228 时执行一次 stop-app，续租后等待 45 秒。
3. 仍失败时，在全局预算允许的情况下执行一次 force-stop → unstop；随后继续保护并等待，不再执行第三次重置。

若 force-stop 因 10 分钟间隔或日预算被拦截，第二阶段仍作为最终重置，之后同样进入长等待。

## 保留的安全边界

- iQOO 每个战役最多 2 次 GMS 重置。
- 每个战役最多 1 次 GMS force-stop。
- force-stop 最短间隔 10 分钟，24 小时最多 6 次。
- 战役失败后的自动重试仍使用 2 / 5 / 15 / 30 分钟退避。
- 成功仍要求 5228 连续健康且 GMS 未冻结满 60 秒。
- WhatsApp 投递保护窗、平板熔断救援、Signal 普通保活、Termux/Tailscale 与本地 5555 检查保持不变。

## 新诊断字段与事件

状态 schema 13 新增：

- `nextResetEligibleElapsed`
- `resetWaitReportedForCount`
- `preconnectionLeaseRequestedElapsed`

新增或强化事件：

- `gms_recovery_preconnection_lease_requested`
- `gms_recovery_reset_deferred_preconnection_lease`
- `gms_binder_stabilization_lease_extended`

验收重点不再是“PID 是否换过”，而是：预连接租约是否从战役开始持续存在，以及 5228 是否在最终重置后的长等待窗口中建立并稳定。
