# 努昂诺塔 1.3.3 基础性能测试

测试日期：2026-07-24  
设备：Redmi 22011211C，Android 14，1080×2400，120 Hz  
构建：Debug，`com.yubegreen.luonnotar`，versionCode 7  
APK SHA-256：`E7E095E6CD5B4AA56DA0CE342B3D8FCAFDC585813A0A8FA868E81D79044817AA`

## 冷启动

使用 `am force-stop` 后通过 `am start -W` 测得：

- `TotalTime=706 ms`
- `WaitTime=726 ms`

启动后 8 秒内前台守护、锁、Wi-Fi underlay 与 VPN-only 204 均已恢复。

## 滑动与动画

重置 `dumpsys gfxinfo` 后，以六次带 800 ms 间隔的 ADB 往返手势覆盖列表滚动、
触顶/触底弹性过冲、回弹、系统栏渐隐与玻璃层位置更新。

- 总帧数：583
- Janky frames：88（15.09%）
- P50：11 ms
- P90：14 ms
- P95：15 ms
- P99：32 ms

本次屏幕以 120 Hz 运行，单帧预算约 8.3 ms；ADB 注入、Debug 构建和极端连续
滚动都会放大 jank。优化前同场景为 P90/P95/P99=15/17/48 ms。最终版让不使用
背景模糊的纯文本卡片直接跳过逐帧 backdrop 刷新，同时保留主视觉和弹层的真实
液态玻璃。

## 内存与 CPU

冷启动并稳定 8 秒后的 `dumpsys meminfo`：

| 进程 | Total PSS | Total RSS |
|---|---:|---:|
| 主界面 | 110,289 KB | 247,700 KB |
| `:keeper` | 20,855 KB | 114,972 KB |

一次基础 `top` 快照中，主界面约 3%，`:keeper` 约 6%；这是瞬时值，不等同于
长期耗电测试。5 秒本地心跳与最长 5 秒 HTTPS 探测已使用不同 executor，
常规联网间隔仍为 5 分钟。

## 范围

这是基础回归，不替代 Macrobenchmark、6 小时熄屏生存/耗电统计、无精确闹钟
长期分布或固定 Proton 节点下 50–100 条真实通知到达率测试。
