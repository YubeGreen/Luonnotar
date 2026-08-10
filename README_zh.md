# 努昂诺塔（Luonnotar）

## 2.6.1 v134 / r297 Transport Incident 与 vendor bridge 协议看门狗

16:10–20:00 的 OriginOS 无人值守长测证明，r296 的单次恢复机械已经能工作，但更上层的故障生命周期仍然划错了：5228 连续健康 8 秒就会结束一个 bootstrap generation，随后同一个真实 outage 又能在新 generation 里重新消费 soft reset 与全局 force-stop。同一轮长测还抓到 vendor bridge 的 split-brain：shell heartbeat 文件持续新鲜，但 protocol heartbeat 已停更约两小时。r297 因此把 destructive 额度与“恢复成功”的判定提升到跨 generation 持久的 transport incident 层。

- 在 bootstrap 之上新增 **GMS transport incident**。单个 6 分钟 bootstrap 可以超时并重开，但同一个 outage 的 freezer campaign 与 thawed-transport bootstrap 共用一份 destructive ledger：整个 incident 最多实际执行一次 soft tier 和一次 hard tier；一旦 hard tier 已消费，后续也不会再降级补做 stop-app。
- 8 秒连续 5228 只改判为 **socket recovered**，不再代表 outage 结束。incident 只有在 **120 秒 recovery probation** 内没有出现持续 30 秒及以上的 transport collapse，或观察到真实 controlled delivery，才正式关闭；长 collapse 只重置 probation，不重置 soft/hard 额度。
- 增加 hard-reset 疗效遥测：verified hard transition 若在 post-reset grace 内始终没有恢复 socket，或之后 >=30 秒 collapse 击穿 probation，会记录为 ineffective；替换 bootstrap generation 仍不能再领第二次 hard reset。
- 旧 freezer recovery campaign 在任何 transport-unhealthy 的 GMS reset 前也必须先创建/接管同一个 incident，不能绕过 bootstrap 的 destructive ledger。
- 新增 **vendor bridge protocol watchdog**：shell heartbeat 文件新鲜不再能掩盖 protocol heartbeat 失联。确认 protocol stall 后立即撤销 bridge command ownership、重启 bridge，并保持 guardian fallback 不受 suppress；只有新的、身份校验通过的 `READY` 才恢复 bridge ownership。
- 状态新增 `gmsTransportIncident` 以及 bridge protocol-stall/restart/ownership 遥测；status schema 升为 **60**，embedded engine 升为 **r297**，versionCode 升为 **134**。

> 当前主线候选：**2.6.1（versionCode 134）**；内置引擎 **r297**；状态 schema **60**。

## 2.6.1 v133 / r296 OriginOS MCS 防抖与有界硬恢复

真机 v132 日志确认 OriginOS `fast_freezer` 会在健康 5228 建链后反复制造亚秒级 `cgroup.freeze=1`，随后 MCS 短暂掉线；绝大多数边缘由现有 `vendor_bridge_mcs_rebuild` 在约 3–8 秒内恢复。r296 不再把这种瞬时冻结误判成 freezer campaign 的终态，同时给真正持续数分钟的 thawed-MCS 死锁补上一档仍受全局 destructive budget 约束的恢复。

- transport bootstrap 对物理回冻增加 **12 秒连续冻结门**：单次/短暂 `fast_freezer` 进入 `freezer_settle`，不再立即 `refrozen` 或被 `recoverGmsLocked()` 抢走所有权；只有连续冻结超过门限才交还 freezer/process campaign。
- 修复长期 MCS outage 反而因 `lastMcsConnectAttempt` 过旧而拿不到 soft reset 的反向条件；持续缺失分支在 auth 安静时仍可获得一次 `stop-app`。
- 新增 thawed-transport hard-reset gate：同一 bootstrap 已持续 3 分钟、且至少 60 秒没有任何健康 transport 样本时，才申请一次 `force-stop → unstop → Binder pulse/lease → GCM_RECONNECT`。真正执行前仍复用既有 **10 分钟最小间隔 / 24 小时 6 次**全局 force-stop budget；不会绕过 destructive safety gate。
- hard reset 后复用既有 post-force-stop shield，避免新 PID 刚起来又被 OriginOS 立即冻结。普通 3–8 秒 MCS rebuild 成功会持续刷新 `lastHealthyObservedElapsed`，因此不会误触 hard reset。
- 修复 `stable_transport_verified success=true ports=[]`：socket 型成功现在必须由 **final probe 自己仍为 healthy** 才成立；受控 push 实际到达仍保留为更强的成功证据。
- `gmsTransportBootstrap` 新增 transient-refreeze / hard-reset gate 遥测；状态 schema 升为 **59**。embedded engine 升为 **r296**，versionCode 升为 **133**。

> 当前主线候选：**2.6.1（versionCode 133）**；内置引擎 **r296**；状态 schema **59**。

## 2.6.1 v132 / r295 OriginOS 解冻后传输恢复分层

2.6.1 不再把“GMS 已经从 freezer 中解冻”和“FCM/MCS 已恢复”当成同一个成功条件。真机故障现场证明 GMS main/persistent 可以同时 `cgroup.freeze=0`、保持高重要性并拥有有效网络，但 MCS 仍持续离线且伴随 `BAD_AUTHENTICATION`。r295 因此把恢复状态机拆成 freezer/process 层与 thawed-transport 层。

- 新增 `GmsThawedTransportBootstrapPolicy`：VIVO/OriginOS 上只有在 GMS 物理解冻、两个核心进程存在、MCS 持续缺失时才接管；正常动作只做有界 `GCM_RECONNECT` 与既有 Binder stabilization lease。
- `BAD_AUTHENTICATION` 被视为“需要稳定窗口等待 Google 自己重认证”的证据，不再直接触发 `force-stop`；90 秒内仍有新 auth 错误时禁止软重置。
- 网络/VPN 切换、近期 `fast_freezer`、MCS reconnect stall 都可触发 thawed-transport bootstrap。重连持续两分钟且 auth 已安静时，最多允许一次 `am stop-app` 软重置；随后重新建立 Binder pulse、后台策略、stabilization lease 与 GCM reconnect。
- 原 GMS freezer campaign 一旦确认物理解冻而 MCS 连续缺失 6 秒，立即以 `transport_bootstrap_handoff` 交棒，不再继续 reset/refreeze 循环，也不把这次交棒计入 adaptive failure cooldown。
- MCS kick 连续失败时，如果当前物理 cgroup 已解冻，不再升级成新的 destructive campaign；由 transport bootstrap 独占这一阶段。
- 控制面 watcher 额外记录努昂诺塔自己的网络切换与 `push_test_arrival_observed`。受控推送真实到达被视为比某一瞬间 `ss` 看不到 5228/5229/5230 更强的恢复证据。
- 继承 v131 shell-start bootstrap：每次 UID 2000 shell engine 启动会先确认/补齐 Luonnotar → Termux `RUN_COMMAND` 权限，再重申标准 Android/OEM 后台策略，随后才进入 Termux `:8022` 健康检查。
- transactional self-update / hot handoff 核心保持 r294 已验证结构，只提升协议修订号到 **r295**；状态 schema 升为 **58**，新增 `gmsTransportBootstrap` 与网络切换/受控到达证据。

> 当前主线候选：**2.6.1（versionCode 132）**；内置引擎 **r295**；状态 schema **58**。

## 2.6.0 v130 / r294 真·主线整编

v130 不再继续堆特权能力，而是冻结已经在 OriginOS / SDK 36 真机上通过故障注入的控制面自愈架构，把 v127-v129 的救援实验收束成正式主线基线。

- transactional self-update / hot handoff 核心不动，内置引擎仍为 **r294**。
- 将努昂诺塔 SSH `:8025`、固定 ADB `:5555`、可选 Termux SSH `:8022` 统一视为控制面恢复通道，共用 `disabled / healthy / missing_grace / recovery_due / backoff` 五阶段策略。
- shell guardian 不再使用 `service.adb.tcp.port` 判断或描述 `:5555` 是否“配置好”。实测 OriginOS 上该属性可以是 `0`，但 Wireless ADB 本身仍完全可用。
- Wireless ADB 端口主来源固定为真机验证过的 Binder 只读路径：先用 `service call adb 12` 确认 Wi-Fi ADB 支持，再用 transaction 10 取得实际监听端口；app-side snapshot 与 mDNS 只保留 fallback。
- 保留 v129 generation rebind：ADB 维护恢复使用 `EmbeddedAdbService` 自己看到的当前 generation，不再接受 `:keeper` 的跨进程旧 generation 作为恢复事务权威。
- guardian status 新增每条恢复通道的 `phase`、`nextRecoveryEligibleElapsed` 与 ADB `lastRecoveryResult`，状态 schema 升为 **56**。
- ADB / Termux 的端口解析、grace、cooldown 统一收进纯策略 `ControlPlaneRecoveryPolicy`；补上 SDK 36 真机 Binder Parcel（33609 / 42949）解析回归测试，并修正旧的 15 秒 cadence 测试。
- `luoterm` 默认等待从 75 秒提高到 120 秒，覆盖真机双杀时完整自愈窗口；设备选择仍与 `luosfud` 一致，iQOO 使用 `luoterm --iq`。

真机主线验收基线：人工 `pkill sshd` 杀掉 Termux `:8022`，再用 `adb usb` 杀掉固定 `:5555`，adbd restart 同时带走 `:8025`。全程不执行任何 `rescue_*`：`8025` 先由 keeper 重生，随后 guardian 通过 Binder 找到 Wireless ADB 端口并恢复 `5555`，第二次 adbd restart 后 `8025` 再次重生，最终 Termux `8022` 也自行恢复。

> v130 冻结基线：**2.6.0（versionCode 130）**；内置引擎 **r294**；状态 schema **56**。

## 2.5.1 r262 厂商回冻 Recovery Owner + 自更新 PoC

- 保留 r261 Provider-first 引擎控制和 r260 hot handoff。
- VIVO `fast_freezer` 防守 episode 活跃时临时取得 GMS recovery 所有权，旧的 reset/force-stop campaign 只能继续观察，不能并行做破坏性重置。
- 12 秒物理解冻稳定门之后增加 120 秒 stable-hold；hold 内再次回冻继续同一 episode，不再立刻创建新的 seq。
- defense 命令节流从 250 ms 拉到 2.5 s，并移除 VIVO defense fallback 里的 AOSP freeze-adoption；失败时最多再做一组有界 release，不允许单次 fallback 扩散成几十条命令。
- 识别 OriginOS `CachedAppOptimizer` / `mFreezeHandler` NPE 特征，本 bridge 生命周期内标记 framework freezer unsupported，后续不再反复调用必炸的 `am/cmd activity freeze`。
- `Long.MAX_VALUE` 的恢复等待遥测改为 `-1`，不再打印巨大的伪等待时间。
- 同时加入第一阶段 shell-only 静默自更新 PoC：只接受 `/data/local/tmp/luonnotar-self-update/` 下、包名为 `com.yubegreen.luonnotar`、同签名且 versionCode 更高的 APK；先固定快照再校验/写入 `PackageInstaller` session，并利用 UID 2000 已有 `INSTALL_PACKAGES` 能力通过 `PackageInstaller.setPermissionsResult()` 批准必要的 OriginOS pending-user-action。
- 该 PoC 不是任意 APK 安装器，也暂不加入正式自动更新 UI。
- 内置引擎修订号：**262**；状态 schema：**21**。

> 当前本地版本：**2.5.1（versionCode 84）**，包名：`com.yubegreen.luonnotar`。

## 2.5.1 r261 Provider-first 引擎控制 + 热切换

- 保留 r260 的已认证 loopback hot handoff，以及 r259 的持续厂商回冻防守。
- 真机 OriginOS 测试发现：显式 ADB manifest broadcast 可能只返回 `result=0` 而 Receiver 根本不执行，因此电脑侧引擎控制主入口改为现有同步 `adb_runtime_config` ContentProvider。
- Provider 新增 shell-only `engine_status` / `engine_restart`，同时保留 `android.permission.DUMP` 与调用 UID 白名单两层限制。
- `engine_status` 直接读取当前 UID 2000 引擎真实 PID/revision、handoff 能力、配对状态与版本是否收敛，不再相信 APK 版本推断引擎版本。
- `engine_restart` 不清除持久化 Kadb 身份：r260+ 直接 hot handoff；更旧或不可达引擎回退到既有本地 ADB 启动链。只有 adbd 明确拒绝授权时才重新配对。
- 旧 broadcast Receiver 保留兼容，但仓库 host 工具改为 Provider-first。
- 内置引擎修订号：**261**；状态 schema：**20**。

> 当前本地版本：**2.5.1（versionCode 83）**，包名：`com.yubegreen.luonnotar`。

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
