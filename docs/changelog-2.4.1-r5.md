# 努昂诺塔 2.4.1 r5

- versionCode: 67
- 特权引擎 revision: 245
- 状态 schema: 11
- 配置 schema: 6

## 基于 r244 长测的修复

### iQOO / GMS 破坏性恢复限流

r244 在 iQOO 上仍可能反复执行 GMS `force-stop -> unstop`。r5 将一次恢复战役限制为：

- vivo/iQOO 最多 3 次 reset；其他设备最多 4 次。
- vivo/iQOO 每轮最多 1 次 GMS force-stop；其他设备最多 2 次。
- vivo/iQOO 第一轮优先 `stop-app`、Binder pulse、解冻与策略重放，不直接 force-stop。
- vivo/iQOO 自动战役间隔提高到 30 分钟。
- 24 小时内自动战役预算降至 6 次；耗尽后至少退避 2 小时。

### 平板熔断期的非破坏性投递救援

WhatsApp 继任进程触发 OEM 反复冻结熔断后，r5 每 2 分钟最多执行一次非破坏性救援：

- 重放后台策略；
- 解冻 WhatsApp 与 GMS；
- Binder pulse；
- 熄屏时短暂前台唤醒后返回桌面。

该路径不 kill、不 stop-app、不 force-stop，不会重新开启 r243 的重置风暴。

### 本机 ADB TCP 5555 活性探测

- 每 60 秒只读检查本机 5555 是否仍处于 LISTEN。
- 只有曾观察到 5555 健康，或系统属性明确配置为 5555 时才启用监控。
- 连续缺失 90 秒后，最多每 5 分钟重放一次 Termux 策略并短暂唤醒 Termux。
- 不重启 adbd，不修改端口，不破坏现有 Termux 会话。

### Signal 普通保活目标

`org.thoughtcrime.securesms` 加入默认进程和包策略目标。已有 schema 5 配置会自动迁移；未安装时跳过，安装后在下一次策略周期自动写入可验证的 Doze、待机桶、AppOps、netpolicy 与支持的厂商策略。Signal 不加入 WhatsApp 专用的破坏性投递恢复战役。
