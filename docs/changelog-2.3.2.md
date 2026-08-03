# 努昂诺塔 2.3.2

## HyperOS 开机提醒修复

实机日志确认：通知并不是被通知中心拦截，而是 HyperOS 在进程创建前拒绝了 `LOCKED_BOOT_COMPLETED`，系统日志为 `process is not permitted to auto start`。应用代码没有获得执行机会，因此任何通知补发逻辑都不可能运行。

本版在 Shell UID 2000 背景策略中，对已识别的 Xiaomi / Redmi / POCO 设备额外尝试并验证两个 MIUI/HyperOS 隐藏 AppOp：

- `10007`：开机完成广播
- `10008`：后台自启动

两项都使用 `cmd appops get/set --user 0`，仅在 Xiaomi 家族上执行；其他厂商不会接触这些数字操作。策略报告会记录 `oem_appop_boot_completed_10007` 和 `oem_appop_auto_start_10008` 的支持、写入和验证结果。

## 重要边界

这项修复必须在重启前至少成功启动一次 Shell 特权引擎，使策略有机会写入并验证 AppOp。若 ROM 拒绝 Shell 修改或系统设置随后覆盖它，仍需用户在系统设置中手动打开努昂诺塔的“后台自启动”。普通应用无法在系统拒绝创建进程的前提下自行发出通知。
