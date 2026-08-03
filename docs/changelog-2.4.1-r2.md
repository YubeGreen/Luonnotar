# 努昂诺塔 2.4.1 r2（engine revision 242）

- 修复 WhatsApp 已确认冻结且 C2DM/GCM 持续失败时，达到每日重建预算后永久停摆。预算耗尽后改为 2 分钟退避重试。
- 继任进程守护由 2 分钟/4 次扩展为最长 24 小时的渐进退避守护；30 秒连续未冻结才判定恢复。
- 小米/vivo 连续继任冻结时自动从 `am kill` 升级到 `am stop-app`，再升级到经过 `cmd package unstop` 预检与验证的 `force-stop → unstop`。
- `force-stop` 路径不清数据，不在无法确认 unstop 能力时执行，并验证旧 PID 消失及 stopped 标志已清除。
- 保留 2.4.1 的 iQOO GMS force-stop/unstop 与 daily-limit 退避修复。
- versionCode 64；特权引擎 revision 242。
