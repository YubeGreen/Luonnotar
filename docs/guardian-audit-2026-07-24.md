# 努昂诺塔 1.3.3 核心可靠性复审

测试设备：Redmi 22011211C，Android 14。  
最终 APK：versionCode 7 / versionName 1.3.3。

## P0 修复

- `:keeper` 成为核心运行态唯一 SharedPreferences 读写进程。主界面、
  Boot Receiver、FcmHealthMonitor 与 WorkManager 只通过不可导出的
  `GuardianStatusProvider` 读取状态或发送白名单控制命令；LabAlarmReceiver、
  通知监听、ADB 证据 Receiver 与前台服务统一运行在 `:keeper`。
- HTTPS 最近尝试 RTT 与最近成功 RTT 分离。只有 HTTP 204 才会更新成功时间和
  成功 RTT；失败保留上次成功证据并单独记录尝试 RTT、HTTP code、错误和连续失败。
- 从未成功不再视为近期成功，状态明确为 `NO_SUCCESS_EVIDENCE`。绿色 HTTPS 条件为：
  最新 HTTP code=204、连续失败=0、存在成功时间/RTT且成功未过期。
- WorkManager 不再从后台直接启动 FGS，只通过 Provider 请求 `:keeper` 安排恢复
  闹钟。精确闹钟不可用时明确标注不精确调度可能延迟约一小时或更久。
- `LOCKED_BOOT_COMPLETED` 路径不再初始化 WorkManager；服务启动异常时只保留
  direct-boot-aware 的 Provider、AlarmManager 和 FGS 路径。

## P1 修复

- Boot Receiver 改为 `exported=false`，代码首行只接受 BOOT_COMPLETED、
  LOCKED_BOOT_COMPLETED 与 MY_PACKAGE_REPLACED。
- 可见恢复由永久 Boolean 改为 15 秒冷却时间；同一 Activity 生命周期可重复恢复。
- ADB VPN 证据有效期由 24 小时降至 5 分钟，并绑定 boot ID 与 VPN network
  handle。错误 handle 会被 Receiver 拒绝；VPN handle 变化或 VPN 丢失时由
  `:keeper` 立即清除证据。
- HTTPS 探测移至独立单线程 executor，5 秒心跳/漂移调度采用绝对节拍，
  网络超时不再被计入 timer drift。
- Wi-Fi 锁只在可确认的 Wi-Fi underlay 上持有；多物理网络或无可确认 underlay
  时保守显示 `UNDERLAY_UNKNOWN` 且不持有 Wi-Fi 高性能锁。移动网络/未知 underlay
  不再因为 Wi-Fi 锁“不适用”而被判为故障。

## P2 修复

- 日志改为稳定的 `events-<pid>-current.jsonl`；达到 1 MiB 后归档当前文件，
  不再每条事件产生单行文件。
- `user_stop` 不再被 `onDestroy` 覆盖；重新启动时才清空退出原因。
- 删除未调用的首次配置向导；正式首次启动政策门仍以政策版本和正文哈希为准。
- 诊断 ZIP 在后台线程导出，并自动只保留最近三个导出文件。
- `LOCKDOWN_UNVERIFIED`、未 VALIDATED 的默认 VPN、失败后的 RTT 不再显示绿色。
- 图片 EXIF 改用 AndroidX ExifInterface；自定义按钮补全 `performClick()`。

## 实机证据

- 界面关闭守护后 2 秒内文件为 `guardian_enabled=false`、两把锁为 false、
  服务消失，`last_service_exit=user_stop` 未被覆盖。
- 重新启用后前台通知 ID 1107、CPU/Wi-Fi 锁和 VPN-only 204 均恢复。
- 连续两次终止 `:keeper` 均重建到新 PID；第二次仍由
  `visible_activity_recovery` 恢复，证明不再“一生只尝试一次”。
- 错误 VPN network handle 的受保护 ADB 广播被拒绝；当前 handle 的证据被接受。
- 运行态读取到 `HEALTHY`、HTTP 204、成功/尝试 RTT 分离、Wi-Fi underlay，
  最大 timer drift 830 ms。
- 最终日志未发现应用 `FATAL EXCEPTION`。

## 构建验证

`testDebugUnitTest`、`lintDebug`、`assembleDebug`、
`assembleDebugAndroidTest` 全部成功，共 76 项任务。

仍需长期验收：6 小时熄屏耗电、固定 Proton 节点下 50–100 条实际通知到达率，
以及无精确闹钟权限时由系统决定的真实恢复分布。
