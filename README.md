# 努昂诺塔（Luonnotar）

> Android VPN 依赖链守护与诊断工具。当前发行身份：**YubeGreen**。  
> 最新本地版本：**1.5.1 (versionCode 14)**，包名：`com.yubegreen.luonnotar`。

努昂诺塔用于观察并守护 Android 设备上的 VPN 网络路径、前台守护服务、系统恢复链与通知到达证据，帮助排查熄屏后的网络或消息延迟问题。它支持 Proton VPN，以及使用 **Exit Node** 的 Tailscale 场景。

## 它实际做什么

- 运行独立的 `:keeper` 前台服务，提供可见的存活、锁、网络和恢复状态。
- 只在系统默认网络确认具有 `TRANSPORT_VPN` 时，通过该 `Network` 执行低频 HTTPS 204 探测；VPN 缺失时停止请求，绝不回落至直连网络。
- 使用 Partial WakeLock，并仅在识别到 Wi-Fi 底层网络时按需持有 Wi-Fi Lock。
- 监测 VPN、默认网络、验证能力、服务心跳、探测结果、恢复闹钟和通知渠道。
- 在服务被异常停止时通过精确闹钟、不精确保险闹钟、WorkManager 与用户可点击恢复通知组成受系统限制的恢复链。
- 可选记录 WhatsApp、WhatsApp Business 与 GMS 的目标包通知事件，作为本机“通知到达观察”线索。
- 提供液态玻璃风格的机型专用 ADB 稳定性与路由证据说明卡片。

主页面标题下方的“核心操作”即为唯一的守护开关：

- 未启用：**开启极限保活**
- 已暂停：**继续极限保活**
- 正在运行：**停止极限保活**

## 不做什么

努昂诺塔不是 VPN，也不是 FCM 客户端或 WhatsApp 修改器：

- 不实现 `VpnService`，不建立或控制 Proton/Tailscale 隧道。
- 不 Root、不 Hook、不使用无障碍自动点击 VPN 应用。
- 不读取、伪造或直接连接 WhatsApp/GMS 的私有 FCM token、socket 或 heartbeat。
- 不承诺绕过 Android Doze、厂商后台冻结或 Android 12+ 的后台前台服务启动限制。
- 不把一次 `connectivitycheck.gstatic.com` 的 HTTPS 204 误称为 FCM 或 WhatsApp 已成功投递。

`VPN_PATH_HEALTHY` 的准确含义是：本代守护服务在当前 VPN network handle 上获得了新鲜的 HTTPS 204 证据，且当前 VPN/验证条件满足。它不等于 `FCM_DELIVERY_VERIFIED`。

## 快速开始

1. 安装 APK 并阅读首次启动政策。
2. 在 Proton VPN 或 Tailscale 中先建立 VPN。
3. Tailscale 必须启用实际承载公网流量的 **Exit Node**；仅连入 tailnet 不代表 FCM 公网路径进入隧道。
4. 在努昂诺塔首页点击“开启极限保活”。
5. 为努昂诺塔和当前 VPN 允许通知、后台活动/高耗电、电池不限制，并将它们固定在最近任务中。
6. 如需观察通知到达，在“通知到达验证模式”中按系统提示授予通知监听权限。

拆分隧道模式可以关闭 Android 的“屏蔽未使用 VPN 的连接”（Lockdown）。但必须由用户确保 GMS、WhatsApp 和 WhatsApp Business 没有被 Proton/Tailscale 的分流规则排除；VPN 真正断开时，普通 APK 无法提供系统级全局断网保护。

## 状态说明

| 状态 | 含义 |
| --- | --- |
| `WAITING_FOR_VPN` | 守护已启用，但本次开机后尚未观察到 VPN。 |
| `VPN_LOST` | 曾观察到 VPN，当前已丢失；应用已停止 HTTPS 请求。 |
| `VPN_UNVALIDATED` | VPN 存在，但当前互联网验证能力不足。 |
| `NO_SUCCESS_EVIDENCE` | 本代服务尚无成功的 VPN-only 204 证据。 |
| `KEEPALIVE_DEGRADED` | 探测连续失败，或成功证据已过期。 |
| `VPN_PATH_HEALTHY` | 当前 VPN 路径的 HTTPS 204 证据新鲜；不等于真实 FCM 投递成功。 |
| `PAUSED` | 用户暂停了守护。 |

绿色仅表示对应条件已满足；未验证或黄色状态不是“系统已坏”，而是当前普通 APK 没有足够证据得出更强结论。

## ADB 辅助验证（可选）

ADB 不是努昂诺塔安装后运行的依赖。它只在需要验证 Always-on、Lockdown、目标 UID 是否被 VPN 覆盖、或 Tailscale Exit Node 公网路由时提供额外诊断。

应用可接收受 `android.permission.DUMP` 保护的 ADB 导入声明。导入内容会绑定当前 boot ID、VPN network handle 与 5 分钟有效期；VPN 切换或消失时立即失效。应用会重算导入字段的指纹，但不能自行证明电脑传入的 dumpsys 结论，因此界面称其为 **ADB 导入证据**，而不是应用自主验证。

建议先在电脑端检查系统网络、VPN 管理状态与目标 UID 覆盖范围，再按应用内“机型专用 ADB 稳定性与路由证据”卡片给出的机型说明操作。

## 恢复与功耗边界

守护服务存活时，CPU 锁、前台优先级和低频 VPN-only 探测可减少部分空闲或路径失效问题。它们不能突破系统的所有限制：

- Android Doze 会限制普通应用网络和闹钟；`allow-while-idle` 在深度待机下不能保证每分钟运行。
- Android 12+ 通常禁止后台直接启动前台服务；不精确闹钟恢复会退化为异常通知，需用户点按。
- Android 14+ 的 Wi-Fi 高性能锁不能被视为息屏下持续高性能 Wi-Fi 的保证。
- vivo/iQOO、HyperOS 等厂商策略仍可能冻结应用或 VPN；应用内向导只提供设置建议，不能静默修改厂商白名单。

因此，努昂诺塔不承诺“一分钟自动恢复上限”，也不承诺在干净设备上自动获得与已手动配置白名单设备相同的保活效果。

## 构建

要求 JDK 17 与 Android SDK。

```powershell
$env:JAVA_HOME='H:\Android\PushTrace\.tools\jdk-17.0.19+10'
.\gradlew.bat testDebugUnitTest lintDebug assembleRelease bundleRelease
```

输出位置：

- APK：`app/build/outputs/apk/release/Luonnotar-release.apk`
- AAB：`app/build/outputs/bundle/release/app-release.aab`

发行构建读取项目根目录的 `keystore.properties`。不要提交或泄露该文件和签名密钥；遗失 YubeGreen 发行密钥后，已安装用户无法获得同包名的覆盖更新。

## 隐私

- 运行日志、状态快照和诊断 ZIP 均存储于本机应用私有目录，导出由用户主动触发。
- 通知到达验证仅观察目标包通知事件；它不是 WhatsApp/FCM 消息正文的服务器端审计。
- 不收集或上传分析数据；本项目不包含后端推送测试服务。

## 交付物与验证

当前已生成的本地发行文件：

- `Luonnotar-1.5.1-YubeGreen-release.apk`
- `Luonnotar-1.5.1-YubeGreen-release.aab`

本版本已完成 Debug 单元测试、Debug Lint 与两台 ADB 设备 APK 更新检查。长期结论仍应以受控发送时间、目标应用通知到达时间、VPN 路由证据和实际熄屏窗口共同判断。
