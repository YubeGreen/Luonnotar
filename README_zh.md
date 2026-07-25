# 努昂诺塔（Luonnotar）

> 此文件保留旧版技术记录；请以项目根目录的 [`README.md`](README.md) 为当前版本说明。

努昂诺塔是一个不占用 Android VPN 槽位的 Proton VPN / Tailscale 依赖链守护与诊断 APK。它用独立前台服务持有 CPU/Wi-Fi 锁，并且只在默认网络被系统证据确认含 `TRANSPORT_VPN` 时，通过该同一个 `Network` 发起低频 HTTPS 204 连通性验证。

> 恢复边界：Android 12+ 在后台启动前台服务受系统限制。不精确闹钟触发后需要用户点按恢复通知；即使拥有精确闹钟权限，Doze 下 allow-while-idle 也受约 9 分钟频率限制。本项目不承诺一分钟恢复上限。

## 安全边界

- 不实现 `VpnService`，不 Root、不 Hook、不使用无障碍点击 VPN 应用。
- 不读取或伪造 WhatsApp/GMS 的私有 FCM token、socket 或心跳。
- VPN 缺失时，努昂诺塔不发起任何网络请求，也不会回落到普通 `URL.openConnection()`。
- 拆分隧道模式允许保持 Lockdown 关闭，但必须确保 GMS、WhatsApp 等目标 UID
  没有被排除；VPN 完全断开时不会获得系统级全局阻断。
- 仪表盘将不可由普通 APK 读取的项目显示为“未验证”，不会根据 VPN 图标猜测。
- ADB 证据入口受系统 `android.permission.DUMP` 保护，普通应用无法伪造；
  记录同时绑定 boot ID、5 分钟有效期、VPN network handle 和证据 SHA-256
  摘要；VPN 切换或丢失会立即使证据失效。

## 界面与政策

- 深色、浅色、跟随系统；纯色、少偶和自定义图片背景。
- Android 12+ 主视觉与政策页使用真实 RenderEffect 模糊；重复卡片使用低内存
  液态玻璃填充、渐变折射描边、触摸光斑和弹性动画。
- 内容延伸至上下边缘，状态栏与导航栏使用与背景联动的渐隐保护。
- 主界面、政策页和通知操作页固定竖屏，避免状态卡与控制区在旋转时重排。
- 健康状态只使用绿色文字和细描边；玻璃底层保持中性，不再把模糊背景染绿。
- 浅色图片背景启用专用高对比层，不沿用浅色纯色的深色文字参数。
- 所有应用内弹窗和短时提示均使用自制液态玻璃：支持按钮锚点展开/回收、
  背景模糊、下滑关闭与深浅色适配，不使用系统 AlertDialog 或 Toast 外观。
- 首次启动必须把政策滑动至末尾并勾选后才能进入；政策版本或正文哈希变化时
  会重新要求确认。

## 极限版

工程只输出极限版 APK。恢复链由独立前台服务、WakeLock、按需 Wi-Fi Lock、
可见界面 15 秒冷却重试、最短约 9 分钟的 allow-while-idle 自检闹钟和
15 分钟 WorkManager 检查组成。
WorkManager 只请求 `:keeper` 安排闹钟，不从后台直接启动前台服务。精确闹钟
不可用时会退化为 `setAndAllowWhileIdle`；Android 12+ 不会从该不精确闹钟
直接启动前台服务，而是显示需要用户点按的恢复通知。系统仍可能显著延后触发。

## 构建

```powershell
$env:JAVA_HOME='H:\Android\PushTrace\.tools\jdk-17.0.19+10'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease --console=plain
```

输出：

- 正式交付：`app/build/outputs/apk/release/Luonnotar-release.apk`
- 设备回归：`app/build/outputs/apk/debug/Luonnotar-debug.apk`

## ADB 运行态验证（可选）

ADB 不是安装后运行依赖。拆分隧道配置变化后，可用
`dumpsys vpn_management` 与包 UID 清单验证 GMS/WhatsApp UID 仍位于 VPN
覆盖范围。普通 APK 无法可靠读取完整 UID 范围。

当前目标模式支持 Proton VPN 或 Tailscale 活动；拆分隧道场景可使用
Always-on=true、Lockdown=false，但 GMS、WhatsApp 与 WhatsApp Business 必须仍在目标 VPN
的 UID 覆盖范围内。

使用 Tailscale 时需启用 Exit Node，让公网流量实际经由 Tailscale VPN；仅连接 tailnet
不等于 GMS/WhatsApp 的 FCM 公网路径已经进入隧道。

这里的“支持”表示努昂诺塔可在 Tailscale Exit Node 场景下守护和检测 VPN 公网路径。
普通 APK 不能自主确认 VPN 所属应用、Exit Node 或第三方 UID 路由；相关字段只能作为
五分钟有效、绑定 boot 与 VPN network handle 的 ADB 导入证据展示。
界面中的 ADB 证据只负责验证和展示，不参与守护服务、锁或恢复调度。
