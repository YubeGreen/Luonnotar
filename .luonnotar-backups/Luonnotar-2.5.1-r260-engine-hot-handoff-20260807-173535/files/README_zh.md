# 努昂诺塔（Luonnotar）

## 2.5.1 r259 厂商持续回冻防守

- 保留 r258 的唯一冻结命令所有者与 GMS main/persistent 原子组；父服务退出后会按精确身份清理租约，不再残留假 owner。
- 将 vivo/iQOO `fast_freezer` 的连续回冻合并为一个防守 episode，不再每次各自启动恢复。
- 每一代 GMS PID 最多发送一次受限 MCS 重连脉冲；不会累计全局 exhaustion，也不会递归开启 emergency campaign。
- main 与 persistent 必须连续真实解冻 12 秒，才允许报告恢复成功。
- 每次回冻都会重置稳定计时；PID 更换会建立新代次，并获得新的一次重连机会。
- 30 秒从未真实解冻或持续回冻两分钟时只升级一次，同时 bridge 继续防守。
- 内置引擎修订号：**259**；状态 schema：**20**。

> 当前本地版本：**2.5.1（versionCode 81）**，包名：`com.yubegreen.luonnotar`。

## 2.3.7 引擎连接后崩溃热修复

- 防止状态请求超时断开后，服务端写回触发 `Broken pipe` 并杀死 UID 2000 `app_process` 引擎。
- 启动流程进行中暂停普通实时刷新，并将首次重型守护循环移到握手成功之后。
- 内置引擎修订号升级为 237。


## 2.3.6 内置 ADB 端口热修复

- 同时保留所有 `_adb-tls-connect._tcp` 候选端口；旧端口拒绝连接后会立即尝试下一个端口。
- 被拒绝的端口进入 45 秒冷却，重复 mDNS 广播不会再制造两秒一次的重试风暴。
- 发现、启动或失败流程已经存在时，实时刷新不会递归创建新的启动代次。
- 只有 Kadb 连接真正完成后才显示“已连接本机 adbd”。
- 内置引擎修订号升级为 236，覆盖更新后会替换仍加载 2.3.5 APK 的 shell 进程。


> 此文件保留旧版技术记录；请以项目根目录的 [`README.md`](README.md) 为当前版本说明。

努昂诺塔是一个不占用 Android VPN 槽位的 Proton VPN / Tailscale 依赖链守护与诊断 APK。vivo/iQOO 默认采用协作模式：不永久持有 CPU/Wi-Fi 锁，熄屏先静默 120 秒，只在必要任务期间短暂持有 CPU Lock；所有网络诊断都绑定系统确认的同一个 `TRANSPORT_VPN` Network。

> 恢复边界：Android 12+ 在后台启动前台服务受系统限制。不精确闹钟触发后需要用户点按恢复通知；即使拥有精确闹钟权限，Doze 下 allow-while-idle 也受约 9 分钟频率限制。本项目不承诺一分钟恢复上限。

## 安全边界

- 不实现 `VpnService`，不 Root、不 Hook、不使用无障碍点击 VPN 应用。
- 不读取或伪造 WhatsApp/GMS 的私有 FCM token、socket 或心跳。
- VPN 缺失时，努昂诺塔不发起任何网络请求，也不会回落到普通 `URL.openConnection()`。
- 拆分隧道模式允许保持 Lockdown 关闭，但必须确保 GMS、WhatsApp 等目标 UID
  没有被排除；VPN 完全断开时不会获得系统级全局阻断。
- 仪表盘将不可由普通 APK 读取的项目显示为“未验证”，不会根据 VPN 图标猜测。
- ADB 证据入口受系统 `android.permission.DUMP` 保护，普通应用无法伪造；
  记录同时绑定 boot ID、VPN provider、network handle、session fingerprint
  和证据 SHA-256 摘要；重启、VPN 会话切换或明确丢失会使证据失效。

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

## 自适应、ADB 隐身与实验室模式

正式默认使用自适应/协作策略。实验室模式提供 L0–L4 分级 A/B，只有显式
选择 L4 才允许永久 CPU Lock 与高性能 Wi-Fi Lock；强度升高后若 GMS 冻结、
推送延迟、发热或耗电恶化，应立即降级。恢复链由独立前台服务、按需短时 Lock、
可见界面 15 秒冷却重试、最短约 9 分钟的 allow-while-idle 自检闹钟和
15 分钟 WorkManager 检查组成。
ADB 一次性隐身验证：运行 60 秒后自动关闭守护，随后主动关闭前台守护、锁、网络 callback、
tick、探测和恢复闹钟，用于和“纯 ADB”环境进行干净 A/B；独立通知监听仍可在
真实通知到达时由系统唤醒。
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

## 1.7.11：通知检测链诊断加固

1.7.11 不再把“监听器没有识别到”直接等同于“推送没有送达”。通知监听会记录连接、
断开、每次 WhatsApp 回调、解析来源与失败原因；普通聊天正文、联系人和群名不会写入日志。
只有消息正文、文本行以及 MessagingStyle 当前/历史消息可成为受控送达证据，标题和副标题
仅用于诊断。Unicode 空格、方向标记和不可见格式字符已规范化。

监听器连接后会补扫 `activeNotifications`。补扫水位与实时回调水位分开保存，只能给出
“最迟已到达”的上界，不能伪装成实时回调。新增三个受 `android.permission.DUMP` 保护的
ADB 入口，用于读取隐私确认、监听器运行状态、心跳和两类水位，以及主动请求补扫。

`tools/watch-push-test-recovery.ps1` 每轮短暂打开发送 CSV，兼容 iCloud 原子替换、BOM 和
半行，不再长期锁文件，也不会把启动前的历史发送当成当前积压。脚本默认只观察；只有显式
加入 `-EnableRecovery`，并且隐私、监听器、心跳、持久化水位和两轮主动补扫全部健康时，
才可能执行有次数上限的 ADB GMS 恢复。
