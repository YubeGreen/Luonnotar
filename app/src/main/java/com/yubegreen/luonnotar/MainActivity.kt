package com.yubegreen.luonnotar

import android.Manifest
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.yubegreen.luonnotar.monitor.FcmHealthEvidence
import com.yubegreen.luonnotar.monitor.FcmHealthMonitor
import com.yubegreen.luonnotar.monitor.AdbVpnEvidencePolicy
import com.yubegreen.luonnotar.monitor.GuardianState
import com.yubegreen.luonnotar.monitor.SupportedVpnProvider
import com.yubegreen.luonnotar.notification.NotificationChannelManager
import com.yubegreen.luonnotar.notification.RecoveryNotificationAvailability
import com.yubegreen.luonnotar.policy.PolicyActivity
import com.yubegreen.luonnotar.policy.PolicyGate
import com.yubegreen.luonnotar.service.FcmGuardianService
import com.yubegreen.luonnotar.service.GuardianLiveness
import com.yubegreen.luonnotar.service.GuardianStatusClient
import com.yubegreen.luonnotar.ui.motion.ElasticNestedScrollView
import com.yubegreen.luonnotar.ui.motion.GuardianActionButton
import com.yubegreen.luonnotar.ui.visual.BackgroundImageStore
import com.yubegreen.luonnotar.ui.visual.AdaptiveLayout
import com.yubegreen.luonnotar.ui.visual.AdaptiveMaxWidthLinearLayout
import com.yubegreen.luonnotar.ui.visual.BackgroundPreference
import com.yubegreen.luonnotar.ui.visual.BackgroundScale
import com.yubegreen.luonnotar.ui.visual.GlassMessageDialog
import com.yubegreen.luonnotar.ui.visual.LiquidGlassDrawable
import com.yubegreen.luonnotar.ui.visual.LiquidGlassPanel
import com.yubegreen.luonnotar.ui.visual.ThemePreference
import com.yubegreen.luonnotar.ui.visual.ThemeTransitionSnapshot
import com.yubegreen.luonnotar.ui.visual.VisualBackgroundView
import com.yubegreen.luonnotar.ui.visual.VisualPreferences
import com.yubegreen.luonnotar.ui.visual.VisualSettingsDialog
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import com.yubegreen.luonnotar.worker.FcmRecoveryWorker
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private lateinit var statusContainer: GridLayout
    private lateinit var serviceButton: Button
    private lateinit var aggressiveModeButton: Button
    private lateinit var serviceActionHint: TextView
    private lateinit var scrollView: ElasticNestedScrollView
    private lateinit var rootContainer: FrameLayout
    private lateinit var visualBackground: VisualBackgroundView
    private lateinit var visualSummary: TextView
    private lateinit var topSystemBarFade: View
    private lateinit var bottomSystemBarFade: View
    private val glassPanels = mutableListOf<LiquidGlassPanel>()
    private var themeTransitionOverlay: View? = null
    private var themeTransitionBitmap: Bitmap? = null
    private var pendingBackgroundImport: PendingBackgroundImport? = null
    private var transientNotice: View? = null
    private var lastVisibleRecoveryAttemptElapsed = Long.MIN_VALUE
    private var lastEnvironmentInspectionElapsed = Long.MIN_VALUE
    private var cachedVpnProviders = emptyList<SupportedVpnProvider>()
    private var cachedFcmEvidence: FcmHealthEvidence? = null
    private val bootId: String by lazy {
        runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }
            .getOrDefault("unavailable")
    }
    private val statusRows = linkedMapOf<String, StatusViews>()
    private var entranceSequence = 0
    private val tabletLayout by lazy { AdaptiveLayout.isTablet(this) }
    private val twoColumnStatus by lazy { AdaptiveLayout.isWideTablet(this) }
    private val backgroundPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::importBackground) ?: run { pendingBackgroundImport = null }
    }
    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            mainHandler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = VisualPreferences.nightMode(VisualPreferences.load(this).theme)
        super.onCreate(savedInstanceState)
        if (!PolicyGate.allowsMainUi(this)) {
            startActivity(Intent(this, PolicyActivity::class.java))
            finish()
            return
        }
        configureSystemBars()
        setContentView(buildContent())
        installThemeTransitionOverlay()
        mainHandler.post {
            scrollView.scrollTo(0, 0)
            finishThemeTransition()
            consumeStatusMessage(intent)
        }
        LogManager.event(this, "dashboard_opened")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mainHandler.post { consumeStatusMessage(intent) }
    }

    private fun consumeStatusMessage(intent: Intent?) {
        intent?.getStringExtra(EXTRA_STATUS_MESSAGE)?.takeIf { it.isNotBlank() }?.let {
            showGlassNotice(it, long = true)
            intent.removeExtra(EXTRA_STATUS_MESSAGE)
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_VPN_CHOOSER, false) == true) {
            intent.removeExtra(EXTRA_OPEN_VPN_CHOOSER)
            showVpnChooser()
        }
    }

    override fun onResume() {
        super.onResume()
        recoverStaleGuardianFromVisibleActivity()
        val status = readGuardianStatus()
        if (
            status.boolean(LuonnotarPreferences.KEY_ENABLED) &&
            !status.boolean(LuonnotarPreferences.KEY_PAUSED)
        ) {
            GuardianStatusClient.scheduleRecoveryAlarm(this)
            FcmRecoveryWorker.ensurePeriodic(this)
        }
        mainHandler.post(refresh)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        clearThemeTransitionOverlay()
        transientNotice?.animate()?.cancel()
        transientNotice = null
        backgroundExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildContent(): View {
        val palette = palette()
        val contentEdge = if (tabletLayout) 24 else 18
        val root = AdaptiveMaxWidthLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            maximumWidthPx = AdaptiveLayout.contentMaximumWidthPx(this@MainActivity)
            isFocusableInTouchMode = true
            requestFocus()
            setPadding(dp(contentEdge), dp(if (tabletLayout) 32 else 24), dp(contentEdge), dp(40))
            setBackgroundColor(Color.TRANSPARENT)
        }
        val headerContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(if (tabletLayout) 24 else 18),
                dp(if (tabletLayout) 22 else 18),
                dp(if (tabletLayout) 24 else 18),
                dp(if (tabletLayout) 22 else 18)
            )
        }
        headerContent.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_luonnotar)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = roundedBackground(Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(
            dp(if (tabletLayout) 80 else 68),
            dp(if (tabletLayout) 80 else 68)
        ).apply { marginEnd = dp(if (tabletLayout) 20 else 16) })
        headerContent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "努昂诺塔"
                textSize = if (tabletLayout) 32f else 28f
                setTextColor(palette.foreground)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = "●  系统证据模式"
                textSize = if (tabletLayout) 17f else 14f
                setTextColor(palette.warning)
                setPadding(0, dp(5), 0, 0)
            })
        })
        val header = glassPanel(headerContent, 28, false)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
        })
        animateEntrance(header, 20L)

        root.addView(TextView(this).apply {
            text = "核心操作"
            textSize = if (tabletLayout) 23f else 18f
            setTextColor(palette.foreground)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(2), 0, 0, dp(if (tabletLayout) 14 else 10))
        })
        serviceButton = primaryGuardianButton("开启极限保活") { toggleService() }
        root.addView(serviceButton)
        serviceActionHint = TextView(this).apply {
            text = "轻触开启前台守护、VPN-only 探测与自动恢复链"
            textSize = if (tabletLayout) 16f else 13f
            setTextColor(palette.secondary)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(1), dp(8), dp(if (tabletLayout) 26 else 20))
        }
        root.addView(serviceActionHint)

        root.addView(TextView(this).apply {
            text = "守护状态"
            textSize = if (tabletLayout) 23f else 18f
            setTextColor(palette.foreground)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(2), 0, 0, dp(10))
        })
        statusContainer = GridLayout(this).apply {
            columnCount = if (twoColumnStatus) 2 else 1
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        root.addView(statusContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(actionButton("重启守护服务") { restartGuardian() })
        root.addView(actionButton("执行 VPN-only 连通性检测") { manualCheck() })
        aggressiveModeButton = actionButton("vivo/iQOO 激进保活模式") {
            toggleAggressiveMode()
        }
        root.addView(aggressiveModeButton)

        root.addView(sectionTitle("外观"))
        visualSummary = TextView(this).apply {
            textSize = if (tabletLayout) 16f else 13f
            setTextColor(palette.secondary)
            setPadding(dp(4), 0, dp(4), dp(10))
        }
        root.addView(visualSummary)
        root.addView(actionButton("调整界面主题") { showVisualSettingsDialog(it) })
        refreshVisualSummary()

        root.addView(sectionTitle("配置与系统入口"))
        root.addView(actionButton("打开 Proton VPN / Tailscale") { openVpnApp(it) })
        root.addView(actionButton("打开系统 VPN / Always-on 设置") { openVpnSettings() })
        root.addView(actionButton("厂商后台限制向导") { showOemGuide(it) })
        root.addView(actionButton("打开电池优化设置") { openBatterySettings() })
        root.addView(actionButton("机型专用 ADB 稳定性与路由证据") { showAdvancedGuide(it) })

        root.addView(sectionTitle("诊断与隐私"))
        root.addView(actionButton("一键导出诊断包") { exportLogs() })
        root.addView(actionButton("通知到达验证模式") { enableNotificationEvidence(it) })
        root.addView(actionButton("极限自检精确闹钟设置") { openExactAlarmSettings() })
        root.addView(actionButton("打开异常通知渠道设置") { openAlertChannelSettings() })
        root.addView(TextView(this).apply {
            text = "边界：拆分隧道模式下可关闭 Lockdown，但必须确保 GMS、WhatsApp 等目标应用没有被排除；VPN 断开窗口无法获得系统级全局阻断。努昂诺塔自身在 VPN 丢失时不会回落直连。"
            textSize = if (tabletLayout) 16f else 13f
            setTextColor(palette.secondary)
            setPadding(dp(4), dp(22), dp(4), 0)
        })
        scrollView = ElasticNestedScrollView(this).apply {
            isFillViewport = true
            addView(
                root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                )
            )
            setElasticFrameListener {
                glassPanels.forEach(LiquidGlassPanel::invalidateBackdropPosition)
            }
            setOnScrollChangeListener { _, _, _, _, _ ->
                glassPanels.forEach(LiquidGlassPanel::invalidateBackdropPosition)
            }
        }
        visualBackground = VisualBackgroundView(this).apply {
            apply(VisualPreferences.load(this@MainActivity))
        }
        rootContainer = FrameLayout(this).apply {
            addView(
                visualBackground,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                scrollView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            )
            topSystemBarFade = View(this@MainActivity).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isClickable = false
            }
            bottomSystemBarFade = View(this@MainActivity).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isClickable = false
            }
            addView(topSystemBarFade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply {
                gravity = Gravity.TOP
            })
            addView(bottomSystemBarFade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply {
                gravity = Gravity.BOTTOM
            })
        }
        glassPanels.forEach { it.bindBackground(visualBackground) }
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            root.setPadding(
                dp(contentEdge),
                dp(if (tabletLayout) 32 else 24) + safe.top,
                dp(contentEdge),
                dp(40) + safe.bottom
            )
            scrollView.setPadding(safe.left, 0, safe.right, 0)
            scrollView.clipToPadding = false
            updateSystemBarFades(safe.top, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(rootContainer)
        rootContainer.post { glassPanels.forEach(LiquidGlassPanel::refreshBackdrop) }
        return rootContainer
    }

    private fun configureSystemBars() {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val imageContrast = !dark &&
            VisualPreferences.load(this).background != BackgroundPreference.SOLID
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark && !imageContrast
            isAppearanceLightNavigationBars = !dark && !imageContrast
        }
    }

    private fun updateSystemBarFades(statusHeight: Int, navigationHeight: Int) {
        if (!::topSystemBarFade.isInitialized) return
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val imageContrast = !dark &&
            VisualPreferences.load(this).background != BackgroundPreference.SOLID
        val edge = when {
            imageContrast -> 0xD9000000.toInt()
            dark -> 0xE60B0B0D.toInt()
            else -> 0xEBF5F5F7.toInt()
        }
        topSystemBarFade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(edge, Color.TRANSPARENT)
        )
        bottomSystemBarFade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, edge)
        )
        topSystemBarFade.layoutParams = (topSystemBarFade.layoutParams as FrameLayout.LayoutParams).apply {
            height = statusHeight + dp(34)
        }
        bottomSystemBarFade.layoutParams =
            (bottomSystemBarFade.layoutParams as FrameLayout.LayoutParams).apply {
                height = navigationHeight + dp(44)
            }
    }

    private fun renderStatus() {
        val status = readGuardianStatus()
        val statusAvailable =
            status.containsKey(LuonnotarPreferences.KEY_KEEPER_PROCESS_PID)
        val enabled = status.boolean(LuonnotarPreferences.KEY_ENABLED)
        val paused = status.boolean(LuonnotarPreferences.KEY_PAUSED)
        val heartbeat = status.long(LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED)
        val servicePid = status.integer(LuonnotarPreferences.KEY_PID)
        val keeperPid = status.integer(LuonnotarPreferences.KEY_KEEPER_PROCESS_PID)
        val serviceAlive = if (paused) {
            false
        } else {
            !GuardianLiveness.shouldRecover(
                enabled = enabled,
                nowElapsed = SystemClock.elapsedRealtime(),
                heartbeatElapsed = heartbeat,
                servicePid = servicePid,
                keeperProcessPid = keeperPid,
                serviceStartedElapsed =
                    status.long(LuonnotarPreferences.KEY_SERVICE_STARTED_ELAPSED),
                thresholdMs = GuardianLiveness.DASHBOARD_STALE_MS
            ) && enabled
        }
        if (enabled && !paused && !serviceAlive) recoverStaleGuardianFromVisibleActivity()
        val vpn = status.boolean(LuonnotarPreferences.KEY_VPN)
        val validated = status.boolean(LuonnotarPreferences.KEY_VALIDATED)
        val nativeVpnProviderPackage = status.string(
            LuonnotarPreferences.KEY_VPN_PROVIDER_PACKAGE,
            ""
        )
        val nativeVpnProvider =
            SupportedVpnProvider.fromPackage(nativeVpnProviderPackage)
        val nativeInternetRouted =
            status.boolean(LuonnotarPreferences.KEY_VPN_INTERNET_ROUTED)
        val nativeIpv4Default =
            status.boolean(LuonnotarPreferences.KEY_VPN_IPV4_DEFAULT_ROUTE)
        val nativeIpv6Default =
            status.boolean(LuonnotarPreferences.KEY_VPN_IPV6_DEFAULT_ROUTE)
        val adbVerification = readAdbVpnVerification(status)
        val activeProvider = adbVerification?.activePackage
            ?.let(SupportedVpnProvider::fromPackage)
        val whatsappInstalled = isInstalled("com.whatsapp")
        val whatsappBusinessInstalled = isInstalled("com.whatsapp.w4b")
        val targetRoutingVerified = adbVerification?.let {
            activeProvider != null &&
                it.internetRouted &&
                it.gmsRouted &&
                (!whatsappInstalled || it.whatsappRouted) &&
                (!whatsappBusinessInstalled || it.whatsappBusinessRouted)
        } == true
        val bypassableText = adbVerification?.let {
                val good = activeProvider != null && !it.bypassable
                "${yesNo(good)} · ADB 导入的目标 UID 证据" to good
            } ?: knownStatus(
                status,
                LuonnotarPreferences.KEY_BYPASSABLE_KNOWN,
                LuonnotarPreferences.KEY_BYPASSABLE,
                invert = true
            )
        val alwaysOn = adbVerification?.let {
            val good = it.alwaysOn && activeProvider != null
            "${yesNo(good)} · ADB 导入 ${verificationAge(it)}" to good
        } ?: knownStatus(
            status,
            LuonnotarPreferences.KEY_ALWAYS_ON_KNOWN,
            LuonnotarPreferences.KEY_ALWAYS_ON
        )
        val lockdown = adbVerification?.let {
            if (it.lockdown) {
                "开启 · 全局阻断 · ADB 导入证据" to true
            } else {
                "关闭 · 拆分隧道兼容 · ADB 导入证据" to true
            }
        } ?: if (status.boolean(LuonnotarPreferences.KEY_LOCKDOWN_KNOWN)) {
            if (status.boolean(LuonnotarPreferences.KEY_LOCKDOWN)) {
                "开启 · 全局阻断" to true
            } else {
                "关闭 · 拆分隧道兼容" to true
            }
        } else {
            "普通 APK 无权确认 · 拆分目标需核对" to false
        }
        val (vpnProviders, gms) = environmentEvidence()
        val lastAttemptRtt = status.long(LuonnotarPreferences.KEY_LAST_ATTEMPT_RTT, -1)
        val lastSuccessfulRtt = status.long(LuonnotarPreferences.KEY_LAST_SUCCESS_RTT, -1)
        val lastHttpCode = status.integer(LuonnotarPreferences.KEY_LAST_HTTP_CODE, -1)
        val lastSuccess = status.long(LuonnotarPreferences.KEY_LAST_SUCCESS_ELAPSED)
        val failures = status.integer(LuonnotarPreferences.KEY_CONSECUTIVE_FAILURES)
        val state = status.string(
            LuonnotarPreferences.KEY_STATE,
            if (statusAvailable) GuardianState.DISABLED.name else "STATUS_UNAVAILABLE"
        )
        val successAge = SystemClock.elapsedRealtime() - lastSuccess
        val hasFreshSuccess = lastSuccess > 0 &&
            successAge in 0..KEEPALIVE_SUCCESS_FRESH_MS
        val successGenerationMatches =
            status.long(
                LuonnotarPreferences.KEY_SUCCESS_EVIDENCE_GENERATION,
                -1L
            ) ==
                status.long(LuonnotarPreferences.KEY_SERVICE_GENERATION, -2L)
        val attemptGenerationMatches =
            status.long(
                LuonnotarPreferences.KEY_ATTEMPT_EVIDENCE_GENERATION,
                -1L
            ) ==
                status.long(LuonnotarPreferences.KEY_SERVICE_GENERATION, -2L)
        val successNetworkMatches =
            status.long(
                    LuonnotarPreferences.KEY_LAST_SUCCESS_NETWORK_HANDLE,
                    -1L
                ) == status.long(LuonnotarPreferences.KEY_NETWORK_HANDLE, -2L)
        val attemptNetworkMatches =
            status.long(
                LuonnotarPreferences.KEY_LAST_ATTEMPT_NETWORK_HANDLE,
                -1L
            ) == status.long(LuonnotarPreferences.KEY_NETWORK_HANDLE, -2L)
        val httpsHealthy = lastHttpCode == 204 &&
            failures == 0 &&
            lastSuccessfulRtt >= 0 &&
            hasFreshSuccess &&
            enabled &&
            !paused &&
            serviceAlive &&
            vpn &&
            validated &&
            successGenerationMatches &&
            successNetworkMatches

        addStatus(
            "Keeper 状态通道",
            if (statusAvailable) "已连接 · PID $keeperPid" else "不可读取 · 控制按钮已锁定",
            statusAvailable
        )
        addStatus(
            "守护服务",
            when {
                !statusAvailable -> "状态不可读"
                paused -> "已暂停 · 锁、心跳、探测与前台服务均已停止"
                serviceAlive -> "运行中 · PID ${status.integer(LuonnotarPreferences.KEY_PID, -1)}"
                enabled -> "已启用但心跳缺失"
                else -> "未启用"
            },
            serviceAlive && !paused
        )
        addStatus(
            "状态机",
            if (!serviceAlive && enabled && !paused) "$state · 服务心跳缺失" else state,
            serviceAlive && state == GuardianState.VPN_PATH_HEALTHY.name
        )
        addStatus(
            "支持的 VPN",
            vpnProviders.joinToString(" / ") { it.displayName }.ifBlank { "未安装" },
            vpnProviders.isNotEmpty()
        )
        addStatus(
            "当前 VPN Provider",
            nativeVpnProvider?.let {
                "${it.displayName} · Android VPN owner UID 证据"
            } ?: if (vpn) {
                "未知 · 当前 Android 版本未公开受支持 Provider 所有者"
            } else {
                "无活动 VPN"
            },
            serviceAlive && nativeVpnProvider != null
        )
        addStatus(
            "VPN 公网默认路由",
            if (nativeInternetRouted) {
                "已检测 · IPv4 ${yesNo(nativeIpv4Default)} / IPv6 ${
                    yesNo(nativeIpv6Default)
                }${
                    if (nativeVpnProvider == SupportedVpnProvider.TAILSCALE) {
                        " · Tailscale Exit Node 路径存在"
                    } else {
                        ""
                    }
                }"
            } else if (nativeVpnProvider == SupportedVpnProvider.TAILSCALE) {
                "未检测到 0.0.0.0/0 或 ::/0 · 请检查 Exit Node"
            } else {
                "未检测到 VPN 公网默认路由"
            },
            serviceAlive && vpn && validated && nativeInternetRouted
        )
        addStatus(
            "默认网络是 VPN",
            "${yesNo(vpn)} · VALIDATED ${yesNo(validated)}",
            serviceAlive && vpn && validated
        )
        addStatus(
            "VPN 不可绕过",
            bypassableText.first,
            serviceAlive && bypassableText.second
        )
        addStatus(
            "Always-on VPN",
            alwaysOn.first,
            serviceAlive && alwaysOn.second
        )
        addStatus(
            "Lockdown / 拆分隧道",
            lockdown.first,
            serviceAlive && lockdown.second
        )
        addStatus(
            "目标 UID 路由",
            if (targetRoutingVerified) {
                "TARGET_UID_ROUTING_VERIFIED · GMS 与已安装的 WhatsApp 目标均由 ${activeProvider?.displayName} 覆盖"
            } else {
                "TARGET_UID_ROUTING_UNVERIFIED · 普通 APK 无法自行证明第三方 UID 路由"
            },
            serviceAlive && targetRoutingVerified
        )
        if (adbVerification != null) {
            val good = activeProvider != null &&
                adbVerification.alwaysOn &&
                !adbVerification.bypassable &&
                adbVerification.internetRouted &&
                targetRoutingVerified
            addStatus(
                "ADB 导入的 VPN 运行态",
                "${adbVerification.activePackage} · ${
                    if (adbVerification.lockdown) "全局阻断" else "拆分模式"
                } · Internet 路由 ${yesNo(adbVerification.internetRouted)} · 导入指纹 ${
                    adbVerification.evidenceHash.take(12)
                }… · ${verificationAge(adbVerification)}",
                serviceAlive && good
            )
            statusRows["ADB 导入的 VPN 运行态"]?.row?.visibility = View.VISIBLE
        } else {
            addStatus(
                "ADB 导入的 VPN 运行态",
                "无当前 boot / VPN handle 的新鲜导入证据",
                false
            )
            statusRows["ADB 导入的 VPN 运行态"]?.row?.visibility = View.GONE
        }
        val wakeLockHeld = serviceAlive && status.boolean(LuonnotarPreferences.KEY_WAKE_LOCK)
        val wifiLockHeld = serviceAlive && status.boolean(LuonnotarPreferences.KEY_WIFI_LOCK)
        val underlyingTransport = status.string(LuonnotarPreferences.KEY_TRANSPORT, "UNKNOWN")
        val aggressiveMode =
            status.boolean(LuonnotarPreferences.KEY_AGGRESSIVE_VIVO_MODE)
        val vivoFamily = isVivoFamily()
        addStatus(
            "vivo/iQOO 激进保活",
            when {
                aggressiveMode ->
                    "已开启 · 熄屏 30 秒 VPN-only 探测 · 亮屏 5 分钟"
                vivoFamily ->
                    "未开启 · 当前机型建议启用以保持 VPN 路径活跃"
                else ->
                    "未开启 · 仅建议在 vivo/iQOO 或明确需要时使用"
            },
            aggressiveMode || !vivoFamily
        )
        val wifiRequired = underlyingTransport == "WIFI"
        val underlayKnown = underlyingTransport in
            setOf("WIFI", "CELLULAR", "ETHERNET")
        addStatus(
            "CPU / Wi-Fi 锁",
            when {
                wifiRequired && Build.VERSION.SDK_INT >= 34 ->
                    "${yesNo(wakeLockHeld)} / ${yesNo(wifiLockHeld)} · Android 14+ 息屏不保证 Wi-Fi 高性能"
                wifiRequired -> "${yesNo(wakeLockHeld)} / ${yesNo(wifiLockHeld)}"
                underlayKnown -> "${yesNo(wakeLockHeld)} / 不适用（$underlyingTransport）"
                else -> "${yesNo(wakeLockHeld)} / 底层网络未确认（$underlyingTransport）"
            },
            wakeLockHeld && underlayKnown && (!wifiRequired || wifiLockHeld)
        )
        val idle = getSystemService(android.os.PowerManager::class.java).isDeviceIdleMode
        addStatus(
            "Doze 限制",
            if (idle) {
                "设备正处于 Doze · 应用 WakeLock 不能恢复一般网络访问"
            } else {
                "当前未进入 Doze · WakeLock 仍不代表真实 FCM 连接"
            },
            !idle
        )
        val httpsText = when {
            !serviceAlive && enabled && !paused ->
                "服务心跳缺失 · 旧 HTTP 证据不作为健康依据"
            (lastSuccess > 0L && !successGenerationMatches) ||
                (lastAttemptRtt >= 0L && !attemptGenerationMatches) ||
                (lastSuccess > 0L && !successNetworkMatches) ||
                (lastSuccess <= 0L && lastAttemptRtt >= 0L && !attemptNetworkMatches) ->
                "等待本代服务完成首次 VPN-only 探测"
            lastSuccess <= 0 -> if (lastAttemptRtt >= 0) {
                "尚无 204 成功证据 · 最近尝试 ${lastAttemptRtt}ms · HTTP $lastHttpCode · 失败 $failures"
            } else {
                "尚无 204 成功证据 · 等待首次尝试"
            }
            failures > 0 -> {
                "上次成功 ${lastSuccessfulRtt}ms · 最近尝试 ${lastAttemptRtt}ms · HTTP $lastHttpCode · 失败 $failures"
            }
            !hasFreshSuccess -> "上次成功 ${lastSuccessfulRtt}ms · 已过期"
            else -> "204：${lastSuccessfulRtt}ms · 连续失败 0"
        }
        addStatus("HTTPS 204", httpsText, httpsHealthy)
        addStatus("GMS 包状态", gms.explanation, gms.available)
        addStatus(
            "真实 FCM Canary",
            "NOT_CONFIGURED · 未集成 FirebaseMessagingService、应用 token 与受控发送端",
            false
        )
        addStatus(
            "FCM 传输可达",
            "FCM_TRANSPORT_UNVERIFIED · HTTPS 204 不能证明 GMS mtalk 持久连接",
            false
        )
        addStatus(
            "FCM 真实送达",
            "FCM_DELIVERY_UNVERIFIED · 需受控发送时间与应用接收时间配对",
            false
        )
        val notificationAccess =
            NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val notificationAcknowledged =
            status.boolean(LuonnotarPreferences.KEY_NOTIFICATION_PRIVACY_ACK)
        val notificationConnected =
            status.boolean(LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_CONNECTED) &&
                status.integer(LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_PID) > 0 &&
                status.integer(LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_PID) == keeperPid
        val arrivalCount = status.long(LuonnotarPreferences.KEY_NOTIFICATION_COUNT)
        val notificationUpdateCount =
            status.long(LuonnotarPreferences.KEY_NOTIFICATION_UPDATE_COUNT)
        val lastArrivalPackage =
            status.string(LuonnotarPreferences.KEY_LAST_NOTIFICATION_PACKAGE)
        val lastArrivalPostWall =
            status.long(LuonnotarPreferences.KEY_LAST_NOTIFICATION_POST_WALL)
        val lastArrivalSeenWall =
            status.long(LuonnotarPreferences.KEY_LAST_NOTIFICATION_SEEN_WALL)
        val lastWasGroupSummary =
            status.boolean(LuonnotarPreferences.KEY_LAST_NOTIFICATION_IS_GROUP_SUMMARY)
        val arrivalText = when {
            !notificationAccess -> "未授权 · 不记录通知内容或到达事件"
            !notificationAcknowledged -> "已授权但尚未完成隐私确认"
            !notificationConnected -> "已授权 · 等待系统连接监听服务"
            lastArrivalPostWall <= 0L -> "监听中 · 尚无 WhatsApp/GMS 到达记录"
            else -> {
                val observationDelay =
                    (lastArrivalSeenWall - lastArrivalPostWall).coerceAtLeast(0L)
                "监听中 · $lastArrivalPackage · 新通知 key $arrivalCount · 通知更新回调 ${
                    notificationUpdateCount
                } · ${if (lastWasGroupSummary) "最近为群组摘要" else "最近为非摘要通知"} · 系统投递 ${
                    formatWallClock(lastArrivalPostWall)
                } · 观察延迟 ${observationDelay}ms"
            }
        }
        addStatus(
            "通知到达验证",
            arrivalText,
            notificationAccess && notificationAcknowledged && notificationConnected
        )
        val alarmScheduled = status.long(LuonnotarPreferences.KEY_ALARM_SCHEDULED_ELAPSED)
        val alarmExact = status.boolean(LuonnotarPreferences.KEY_ALARM_EXACT)
        val alarmInsurance = status.boolean(LuonnotarPreferences.KEY_ALARM_INSURANCE)
        val recoveryNotification =
            RecoveryNotificationAvailability.evaluate(this)
        val guardianNotification =
            RecoveryNotificationAvailability.evaluateGuardian(this)
        val alarmText = when {
            !enabled -> "未启用"
            paused -> "已暂停 · 未安排恢复闹钟"
            alarmScheduled <= 0L -> "尚未安排"
            alarmExact && alarmInsurance ->
                "精确 allow-while-idle + 独立不精确保险 · Doze 下最短约 9 分钟"
            alarmExact -> "精确 allow-while-idle · 不精确保险安排失败"
            !recoveryNotification.available ->
                "不精确 · ${recoveryNotification.explanation}"
            else -> "不精确 · Android 12+ 触发后需点按通知恢复"
        }
        addStatus(
            "恢复闹钟",
            alarmText,
            enabled && !paused && alarmExact && alarmInsurance
        )
        addStatus(
            "前台守护通知",
            guardianNotification.explanation,
            guardianNotification.available
        )
        addStatus(
            "异常恢复通知",
            recoveryNotification.explanation,
            recoveryNotification.available
        )
        addStatus("进程 / 调度证据", "重建序号 ${status.long(LuonnotarPreferences.KEY_PROCESS_SEQUENCE)} · 最大漂移 ${status.long(LuonnotarPreferences.KEY_MAX_TIMER_DRIFT)}ms", serviceAlive)
        val recovery = listOf(
            "服务" to status.string(LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE),
            "闹钟" to status.string(LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM),
            "通知" to status.string(
                LuonnotarPreferences.KEY_RECOVERY_FAILURE_NOTIFICATION
            ),
            "开机" to status.string(LuonnotarPreferences.KEY_RECOVERY_FAILURE_BOOT)
        ).filter { it.second.isNotBlank() }
        if (recovery.isNotEmpty()) {
            addStatus(
                "自动恢复异常",
                recovery.joinToString(" · ") { "${it.first}：${it.second}" },
                false
            )
            statusRows["自动恢复异常"]?.row?.visibility = View.VISIBLE
        } else {
            statusRows["自动恢复异常"]?.row?.visibility = View.GONE
        }
        serviceButton.text = when {
            !enabled -> "开启极限保活"
            paused -> "继续极限保活"
            else -> "停止极限保活"
        }
        serviceButton.contentDescription = "${serviceButton.text}，核心操作"
        aggressiveModeButton.text = when {
            aggressiveMode -> "关闭 vivo/iQOO 激进保活"
            vivoFamily -> "开启 vivo/iQOO 激进保活（建议）"
            else -> "开启激进保活（仅 vivo/iQOO 建议）"
        }
        aggressiveModeButton.contentDescription =
            "${aggressiveModeButton.text}，熄屏时每 30 秒执行 VPN-only HTTPS 探测"
        serviceButton.isEnabled = statusAvailable
        serviceButton.isSelected = statusAvailable && (!enabled || paused)
        (serviceButton.background as? LiquidGlassDrawable)?.setGood(enabled && !paused)
        serviceActionHint.text = when {
            !statusAvailable -> "状态通道暂不可用，请稍后重试"
            !enabled -> "轻触开启前台守护、VPN-only 探测与自动恢复链"
            paused -> "极限保活已暂停，轻触即可继续"
            else -> "极限保活运行中 · 轻触可停止"
        }
        serviceActionHint.setTextColor(
            when {
                !statusAvailable -> palette().warning
                enabled && !paused -> palette().good
                else -> palette().secondary
            }
        )
        refreshVisualSummary()
    }

    private fun addStatus(label: String, value: String, good: Boolean) {
        val palette = palette()
        statusRows[label]?.let { existing ->
            (existing.row as? LiquidGlassPanel)?.setGood(good)
            val targetColor = if (good) palette.good else palette.warning
            if (existing.value.text.toString() == value || !animationsEnabled()) {
                existing.value.text = value
                existing.value.setTextColor(targetColor)
            } else {
                existing.value.animate().cancel()
                existing.value.animate()
                    .alpha(0f)
                    .translationY(-dp(4).toFloat())
                    .setDuration(80L)
                    .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
                    .withEndAction {
                        existing.value.text = value
                        existing.value.setTextColor(targetColor)
                        existing.value.translationY = dp(5).toFloat()
                        existing.value.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(180L)
                            .setInterpolator(PathInterpolator(0.16f, 1f, 0.3f, 1f))
                            .start()
                    }
                    .start()
            }
            return
        }
        val rowContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(if (tabletLayout) 20 else 16),
                dp(if (tabletLayout) 17 else 13),
                dp(if (tabletLayout) 20 else 16),
                dp(if (tabletLayout) 17 else 13)
            )
        }
        val labelView = TextView(this).apply {
            text = label
            textSize = if (tabletLayout) 15.5f else 13f
            setTextColor(palette.secondary)
        }
        val valueView = TextView(this).apply {
            text = value
            textSize = if (tabletLayout) 19f else 16f
            setLineSpacing(0f, if (tabletLayout) 1.12f else 1f)
            setTextColor(if (good) palette.good else palette.warning)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        rowContent.addView(labelView)
        rowContent.addView(valueView)
        val row = glassPanel(rowContent, if (tabletLayout) 24 else 20, good)
        if (::visualBackground.isInitialized) row.bindBackground(visualBackground)
        statusContainer.addView(
            row,
            GridLayout.LayoutParams().apply {
                width = if (twoColumnStatus) 0 else ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = if (twoColumnStatus) {
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                } else {
                    GridLayout.spec(0)
                }
                setMargins(
                    if (twoColumnStatus && statusRows.size % 2 == 1) dp(4) else 0,
                    0,
                    if (twoColumnStatus && statusRows.size % 2 == 0) dp(4) else 0,
                    dp(8)
                )
            }
        )
        statusRows[label] = StatusViews(row, valueView)
        animateEntrance(row, 70L + (statusRows.size - 1).coerceAtMost(8) * 34L)
    }

    private fun toggleService() {
        val status = readGuardianStatus()
        val enabled = status.boolean(LuonnotarPreferences.KEY_ENABLED)
        val paused = status.boolean(LuonnotarPreferences.KEY_PAUSED)
        if (enabled && paused) {
            startGuardianAction(FcmGuardianService.ACTION_RESUME, "visible_user_resume")
            FcmRecoveryWorker.ensurePeriodic(this)
        } else if (enabled) {
            if (!GuardianStatusClient.setEnabled(this, false)) {
                showGlassNotice("无法写入停止状态；守护服务保持运行", long = true)
                return
            }
            runCatching {
                startService(
                    Intent(this, FcmGuardianService::class.java)
                        .setAction(FcmGuardianService.ACTION_STOP)
                )
            }.onFailure {
                GuardianStatusClient.setRecoveryFailure(
                    this,
                    "停止 Intent 发送失败；服务将在下一次 tick 服从 disabled 状态：$it"
                )
                LogManager.event(
                    this,
                    "guardian_stop_intent_failed",
                    mapOf("error" to it.toString())
                )
                showGlassNotice(
                    "停止指令发送失败；权威状态已关闭，服务将在 5 秒内自停",
                    long = true
                )
            }
            FcmRecoveryWorker.cancelPeriodic(this)
        } else {
            if (!GuardianStatusClient.setEnabled(this, true)) {
                showGlassNotice("无法写入守护控制状态", long = true)
                return
            }
            requestNotificationPermissionIfNeeded()
            startGuardian("visible_user_action")
            FcmRecoveryWorker.enqueue(this, "user_enabled")
            FcmRecoveryWorker.ensurePeriodic(this)
        }
    }

    private fun restartGuardian() {
        val status = readGuardianStatus()
        if (!status.boolean(LuonnotarPreferences.KEY_ENABLED)) {
            showGlassNotice("请先开启极限保活")
            return
        }
        if (status.boolean(LuonnotarPreferences.KEY_PAUSED)) {
            showGlassNotice("守护已暂停；请先点“继续极限保活”")
            return
        }
        startGuardianAction(FcmGuardianService.ACTION_RECOVER, "visible_manual_recover")
    }

    private fun startGuardian(reason: String) {
        startGuardianAction(FcmGuardianService.ACTION_START, reason)
    }

    private fun startGuardianAction(action: String, reason: String) {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, FcmGuardianService::class.java)
                    .setAction(action)
                    .putExtra(FcmGuardianService.EXTRA_START_REASON, reason)
            )
        } catch (error: Exception) {
            GuardianStatusClient.setRecoveryFailure(this, error.toString())
            showGlassNotice("启动失败：${error.javaClass.simpleName}", long = true)
        }
    }

    private fun recoverStaleGuardianFromVisibleActivity() {
        val status = readGuardianStatus()
        if (!status.boolean(LuonnotarPreferences.KEY_ENABLED)) return
        if (status.boolean(LuonnotarPreferences.KEY_PAUSED)) return
        val now = SystemClock.elapsedRealtime()
        if (
            lastVisibleRecoveryAttemptElapsed != Long.MIN_VALUE &&
            now - lastVisibleRecoveryAttemptElapsed < VISIBLE_RECOVERY_COOLDOWN_MS
        ) return
        val heartbeat = status.long(LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED)
        val stale = GuardianLiveness.shouldRecover(
            enabled = true,
            nowElapsed = now,
            heartbeatElapsed = heartbeat,
            servicePid = status.integer(LuonnotarPreferences.KEY_PID),
            keeperProcessPid = status.integer(LuonnotarPreferences.KEY_KEEPER_PROCESS_PID),
            serviceStartedElapsed =
                status.long(LuonnotarPreferences.KEY_SERVICE_STARTED_ELAPSED),
            thresholdMs = GuardianLiveness.DASHBOARD_STALE_MS
        )
        if (stale) {
            lastVisibleRecoveryAttemptElapsed = now
            LogManager.event(this, "visible_activity_recovery_requested")
            startGuardian("visible_activity_recovery")
        }
    }

    private fun manualCheck() {
        val status = readGuardianStatus()
        if (!status.boolean(LuonnotarPreferences.KEY_ENABLED)) {
            showGlassNotice("请先开启守护服务")
            return
        }
        if (status.boolean(LuonnotarPreferences.KEY_PAUSED)) {
            showGlassNotice("守护已暂停；继续保活后才能检测")
            return
        }
        runCatching {
            startService(
                Intent(this, FcmGuardianService::class.java)
                    .setAction(FcmGuardianService.ACTION_CHECK)
            )
        }.onSuccess {
            showGlassNotice("检测已提交；无 VPN 时不会发起请求")
        }.onFailure {
            GuardianStatusClient.setRecoveryFailure(
                this,
                "手动检测提交失败：${it.javaClass.simpleName}"
            )
            showGlassNotice("检测提交失败：${it.javaClass.simpleName}", long = true)
        }
    }

    private fun toggleAggressiveMode() {
        val status = readGuardianStatus()
        if (!status.containsKey(LuonnotarPreferences.KEY_KEEPER_PROCESS_PID)) {
            showGlassNotice("Keeper 状态不可读，暂时无法修改激进模式", long = true)
            return
        }
        val enabled =
            !status.boolean(LuonnotarPreferences.KEY_AGGRESSIVE_VIVO_MODE)
        if (!GuardianStatusClient.setAggressiveMode(this, enabled)) {
            showGlassNotice("激进模式设置保存失败", long = true)
            return
        }
        LogManager.event(
            this,
            "aggressive_vivo_mode_changed",
            mapOf("enabled" to enabled, "vivoFamily" to isVivoFamily())
        )
        if (
            enabled &&
            status.boolean(LuonnotarPreferences.KEY_ENABLED) &&
            !status.boolean(LuonnotarPreferences.KEY_PAUSED)
        ) {
            runCatching {
                startService(
                    Intent(this, FcmGuardianService::class.java)
                        .setAction(FcmGuardianService.ACTION_CHECK)
                )
            }
        }
        showGlassNotice(
            if (enabled) {
                "激进模式已开启：熄屏后每 30 秒保持 VPN 路径活跃；FCM 真实交付仍需通知证据验证"
            } else {
                "激进模式已关闭：已恢复 5 分钟 VPN-only 探测周期"
            },
            long = true
        )
        renderStatus()
    }

    private fun isVivoFamily(): Boolean {
        val vendor = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return "vivo" in vendor || "iqoo" in vendor
    }

    private fun showOemGuide(anchorView: View? = null) {
        val vendor = "${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL}"
        val steps = when {
            vendor.contains("vivo", true) || vendor.contains("iqoo", true) ->
                "检测到 vivo/iQOO：\n• 建议在主页开启“vivo/iQOO 激进保活”，熄屏后使用 30 秒 VPN-only 路径探测\n• 努昂诺塔和当前 VPN（Proton/Tailscale）：自启动、后台高耗电、电池不限制、最近任务锁定、常驻通知\n• i 管家不得自动清理\n• WhatsApp/GMS：后台数据与电池不限制，GMS 保持 Doze 白名单\n• 原生 Doze 关闭不代表 vivo PEM/后台冻结已关闭"
            vendor.contains("xiaomi", true) || vendor.contains("redmi", true) ->
                "检测到小米/红米：\n• 努昂诺塔、当前 VPN（Proton/Tailscale）、WhatsApp、GMS 分别开启自启动\n• 电池策略设为“无限制”，允许后台数据，最近任务锁定\n• HyperOS 更新后重新检查\n• millet_white 仅作高级实验，不由本应用修改"
            vendor.contains("oppo", true) || vendor.contains("oneplus", true) || vendor.contains("realme", true) ->
                "开启自启动、关联启动、后台运行、不优化，并锁定最近任务。"
            vendor.contains("huawei", true) || vendor.contains("honor", true) ->
                "应用启动管理改为手动，允许自启动、关联启动、后台活动。若无 GMS，本机不存在可用的 Google FCM 环境。"
            vendor.contains("samsung", true) ->
                "不要加入深度睡眠应用；电池设为不受限制。"
            else -> "请为努昂诺塔与当前 VPN（Proton/Tailscale）开启自启动、后台活动/高耗电、电池不限制，并锁定最近任务。"
        }
        GlassMessageDialog(
            context = this,
            preferences = VisualPreferences.load(this),
            visualBackground = visualBackground,
            title = "厂商适配 · $vendor",
            message = steps +
                "\n\n努昂诺塔会分别记录 Device Idle、PID 重建、CPU 挂起估算、定时器漂移、VPN/Wi-Fi 与开机广播证据。",
            primaryLabel = "尝试打开厂商设置",
            anchorView = anchorView,
            onPrimary = ::openOemSettingsCandidates
        ).show()
    }

    private fun openOemSettingsCandidates() {
        val candidates = mutableListOf<Intent>()
        if (Build.MANUFACTURER.contains("vivo", true) || Build.BRAND.contains("iqoo", true)) {
            candidates += componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            candidates += componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
        }
        if (Build.MANUFACTURER.contains("xiaomi", true)) {
            candidates += componentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        }
        candidates += Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        candidates += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        val launched = candidates.any { candidate ->
            candidate.resolveActivity(packageManager) != null &&
                runCatching { startActivity(candidate) }.isSuccess
        }
        if (!launched) openBatterySettings()
    }

    private fun showAdvancedGuide(anchorView: View? = null) {
        val vendor = "${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL}"
        val script = adbStabilityCommands()
        val guide = """
            当前机型：$vendor

            此 PowerShell 脚本只处理实际已安装的目标包，并在执行后打印 Device Idle、待机桶、AppOps、netpolicy 与 connectivity 核验结果。

            仍须在 vivo/iQOO 设置中人工确认：
            • 自启动允许
            • 关联启动允许
            • 后台高耗电允许
            • 电池策略不限制
            • 后台网络允许
            • 最近任务锁定
            • GMS、WhatsApp、VPN 与努昂诺塔均加入厂商白名单

            ADB 不能证明 GMS 私有 FCM socket 正常；HTTPS 204 只表示努昂诺塔自己的 VPN 路径活跃。

            $script
        """.trimIndent()
        GlassMessageDialog(
            context = this,
            preferences = VisualPreferences.load(this),
            visualBackground = visualBackground,
            title = "机型专用 ADB 建议",
            message = guide,
            monospace = true,
            primaryLabel = "复制安全脚本",
            secondaryLabel = "A/B 诊断命令",
            anchorView = anchorView,
            onPrimary = { copyToClipboard("Luonnotar ADB PowerShell", script) },
            onSecondary = { showDozeAbDiagnosticCommand(anchorView) }
        ).show()
    }

    private fun adbStabilityCommands(): String {
        val vendor = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        val settingsCommands = when {
            "vivo" in vendor || "iqoo" in vendor -> """
                ${'$'}vendorSettingsOpened = ${'$'}false
                ${'$'}vendorResult = adb shell am start -n com.vivo.permissionmanager/.activity.BgStartUpManagerActivity 2>&1
                if (${'$'}LASTEXITCODE -eq 0 -and ${'$'}vendorResult -notmatch "Error|Exception") { ${'$'}vendorSettingsOpened = ${'$'}true }
                if (-not ${'$'}vendorSettingsOpened) {
                  ${'$'}vendorResult = adb shell am start -n com.iqoo.secure/.ui.phoneoptimize.AddWhiteListActivity 2>&1
                  if (${'$'}LASTEXITCODE -eq 0 -and ${'$'}vendorResult -notmatch "Error|Exception") { ${'$'}vendorSettingsOpened = ${'$'}true }
                }
                if (-not ${'$'}vendorSettingsOpened) {
                  adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.yubegreen.luonnotar
                }
            """.trimIndent()
            "xiaomi" in vendor || "redmi" in vendor -> """
                adb shell am start -n com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity
                if (${'$'}LASTEXITCODE -ne 0) {
                  adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.yubegreen.luonnotar
                }
            """.trimIndent()
            else -> """
                adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.yubegreen.luonnotar
                adb shell am start -a android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            """.trimIndent()
        }
        return """
            ${'$'}targets = @(
              "com.yubegreen.luonnotar",
              "com.google.android.gms",
              "com.whatsapp",
              "com.whatsapp.w4b",
              "ch.protonvpn.android",
              "com.tailscale.ipn"
            )
            ${'$'}installed = @()
            foreach (${'$'}package in ${'$'}targets) {
              ${'$'}path = adb shell pm path ${'$'}package 2>${'$'}null
              if (${'$'}LASTEXITCODE -eq 0 -and ${'$'}path -match "^package:") {
                ${'$'}installed += ${'$'}package
                adb shell cmd deviceidle whitelist "+${'$'}package"
                adb shell am set-standby-bucket ${'$'}package active
                adb shell cmd appops set ${'$'}package RUN_IN_BACKGROUND allow
                adb shell cmd appops set ${'$'}package RUN_ANY_IN_BACKGROUND allow
                adb shell cmd appops set ${'$'}package WAKE_LOCK allow
              }
            }
            adb shell cmd deviceidle whitelist
            foreach (${'$'}package in ${'$'}installed) {
              adb shell am get-standby-bucket ${'$'}package
              adb shell cmd appops get ${'$'}package RUN_IN_BACKGROUND
              adb shell cmd appops get ${'$'}package RUN_ANY_IN_BACKGROUND
              adb shell cmd appops get ${'$'}package WAKE_LOCK
            }
            adb shell dumpsys netpolicy
            adb shell dumpsys connectivity
            $settingsCommands
        """.trimIndent()
    }

    private fun showDozeAbDiagnosticCommand(anchorView: View? = null) {
        val command = "adb shell dumpsys deviceidle disable"
        GlassMessageDialog(
            context = this,
            preferences = VisualPreferences.load(this),
            visualBackground = visualBackground,
            title = "仅用于 A/B 故障诊断",
            message =
                "此命令会全局禁用 Android Device Idle，仅用于比较熄屏推送延迟。努昂诺塔不会自动执行，也不建议长期作为默认配置。\n\n$command",
            monospace = true,
            primaryLabel = "复制诊断命令",
            anchorView = anchorView,
            onPrimary = { copyToClipboard("Luonnotar Doze A/B", command) }
        ).show()
    }

    private fun copyToClipboard(label: String, value: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(label, value)
        )
        showGlassNotice("已复制到剪贴板")
    }

    private fun showVisualSettingsDialog(anchorView: View? = null) {
        val current = VisualPreferences.load(this)
        VisualSettingsDialog(
            context = this,
            current = current,
            visualBackground = visualBackground,
            anchorView = anchorView,
            onCustomRequested = { theme, scale ->
                pendingBackgroundImport = PendingBackgroundImport(theme, scale)
                backgroundPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onApply = ::applyVisualPreferences
        ).show()
    }

    private fun importBackground(uri: Uri) {
        showGlassNotice("正在读取背景")
        backgroundExecutor.execute {
            val result = runCatching {
                val bitmap = BackgroundImageStore.decodeFromUri(this, uri)
                try {
                    BackgroundImageStore.saveBitmap(this, bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    val pending = pendingBackgroundImport
                        ?: PendingBackgroundImport(
                            VisualPreferences.load(this).theme,
                            BackgroundScale.FILL_CROP
                        )
                    pendingBackgroundImport = null
                    applyVisualPreferences(
                        pending.theme,
                        BackgroundPreference.CUSTOM_IMAGE,
                        pending.scale
                    )
                    showGlassNotice("背景已应用")
                }.onFailure {
                    pendingBackgroundImport = null
                    showGlassNotice(
                        "背景导入失败：${it.message ?: "未知错误"}",
                        long = true
                    )
                }
            }
        }
    }

    private fun applyVisualPreferences(
        theme: ThemePreference,
        background: BackgroundPreference,
        scale: BackgroundScale
    ) {
        val current = VisualPreferences.load(this)
        val effectiveScale =
            if (background == BackgroundPreference.SHAO_OU) BackgroundScale.FILL_CROP else scale
        val updated = VisualPreferences(theme, background, effectiveScale)
        if (updated == current) return
        ThemeTransitionSnapshot.capture(rootContainer)
        updated.save(this)
        LogManager.event(
            this,
            "visual_preferences_changed",
            mapOf(
                "theme" to theme.name,
                "background" to background.name,
                "backgroundScale" to effectiveScale.name
            )
        )
        if (theme != current.theme) {
            delegate.localNightMode = VisualPreferences.nightMode(theme)
        } else {
            recreate()
        }
    }

    private fun refreshVisualSummary() {
        if (!::visualSummary.isInitialized) return
        val preferences = VisualPreferences.load(this)
        visualSummary.text =
            "主题：${themeLabel(preferences.theme)}    背景：${backgroundLabel(preferences)}\n" +
                "静态背景与主题切换只在界面进程生效，守护服务不受影响"
    }

    private fun installThemeTransitionOverlay() {
        val bitmap = ThemeTransitionSnapshot.take() ?: return
        themeTransitionBitmap = bitmap
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(bitmap)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        themeTransitionOverlay = image
        rootContainer.addView(
            image,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun finishThemeTransition() {
        val overlay = themeTransitionOverlay ?: return
        overlay.postDelayed({
            if (themeTransitionOverlay !== overlay) return@postDelayed
            overlay.animate()
                .alpha(0f)
                .setDuration(340L)
                .setInterpolator(PathInterpolator(0.16f, 1f, 0.3f, 1f))
                .withEndAction { clearThemeTransitionOverlay() }
                .start()
        }, 80L)
    }

    private fun clearThemeTransitionOverlay() {
        val overlay = themeTransitionOverlay
        themeTransitionOverlay = null
        overlay?.animate()?.cancel()
        (overlay?.parent as? ViewGroup)?.removeView(overlay)
        themeTransitionBitmap?.takeUnless { it.isRecycled }?.recycle()
        themeTransitionBitmap = null
    }

    private fun enableNotificationEvidence(anchorView: View? = null) {
        GlassMessageDialog(
            context = this,
            preferences = VisualPreferences.load(this),
            visualBackground = visualBackground,
            title = "通知到达验证（可选）",
            message = "启用后仅记录 WhatsApp/GMS 的 packageName、postTime 与通知 key 的 SHA-256 截断哈希；不保存正文、联系人、群名。关闭权限不影响保活。",
            closeLabel = "取消",
            primaryLabel = "同意并打开授权页",
            anchorView = anchorView,
            onPrimary = {
                if (!GuardianStatusClient.setPrivacyAcknowledged(this)) {
                    showGlassNotice("无法保存通知验证隐私确认", long = true)
                } else {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }.onFailure {
                        showGlassNotice("当前系统没有可用的通知使用权设置入口", long = true)
                    }
                }
            }
        ).show()
    }

    private fun exportLogs() {
        showGlassNotice("正在后台整理诊断日志…")
        backgroundExecutor.execute {
            runCatching { LogManager.exportZip(this) }
                .onSuccess { file ->
                    mainHandler.post {
                        if (isFinishing || isDestroyed) return@post
                        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                        runCatching {
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "导出努昂诺塔诊断日志"))
                        }.onFailure {
                            showGlassNotice("没有可用的诊断文件分享入口", long = true)
                        }
                    }
                }
                .onFailure {
                    mainHandler.post {
                        if (!isFinishing && !isDestroyed) {
                            showGlassNotice("导出失败：${it.message}", long = true)
                        }
                    }
                }
        }
    }

    private fun openVpnApp(anchorView: View? = null) {
        val active = readAdbVpnVerification(readGuardianStatus())
            ?.activePackage
            ?.takeIf(SupportedVpnProvider::isSupported)
        val alwaysOn = runCatching {
            Settings.Secure.getString(contentResolver, "always_on_vpn_app")
        }.getOrNull()?.takeIf(SupportedVpnProvider::isSupported)
        val installed = SupportedVpnProvider.entries.filter {
            isInstalled(it.packageName)
        }
        val target = active ?: alwaysOn ?: installed.singleOrNull()?.packageName
        if (target == null && installed.size > 1) {
            showVpnChooser(anchorView)
            return
        }
        openVpnPackage(target)
    }

    private fun openVpnPackage(target: String?) {
        val launch = target?.let(packageManager::getLaunchIntentForPackage)
        val launched = launch != null && runCatching { startActivity(launch) }.isSuccess
        if (!launched) {
            showGlassNotice("未找到 Proton VPN 或 Tailscale")
            openVpnSettings()
        }
    }

    private fun showVpnChooser(anchorView: View? = null) {
        val protonInstalled = isInstalled(SupportedVpnProvider.PROTON.packageName)
        val tailscaleInstalled = isInstalled(SupportedVpnProvider.TAILSCALE.packageName)
        if (!protonInstalled && !tailscaleInstalled) {
            showGlassNotice("未找到 Proton VPN 或 Tailscale")
            openVpnSettings()
            return
        }
        if (protonInstalled.xor(tailscaleInstalled)) {
            openVpnPackage(
                if (protonInstalled) SupportedVpnProvider.PROTON.packageName
                else SupportedVpnProvider.TAILSCALE.packageName
            )
            return
        }
        GlassMessageDialog(
            context = this,
            preferences = VisualPreferences.load(this),
            visualBackground = visualBackground,
            title = "选择要打开的 VPN",
            message = "普通 APK 无法可靠判断当前 VPN 属于哪个应用。请选择 Proton VPN 或 Tailscale。",
            primaryLabel = "Proton VPN",
            secondaryLabel = "Tailscale",
            anchorView = anchorView,
            onPrimary = {
                openVpnPackage(SupportedVpnProvider.PROTON.packageName)
            },
            onSecondary = {
                openVpnPackage(SupportedVpnProvider.TAILSCALE.packageName)
            }
        ).show()
    }

    private fun openVpnSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
    }

    private fun openBatterySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .recoverCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName")
                    )
                )
            }.onFailure {
                showGlassNotice("当前系统没有可用的精确闹钟设置入口", long = true)
            }
        } else {
            showGlassNotice("Android 11 及以下无需单独授予精确闹钟权限")
        }
    }

    private fun openAlertChannelSettings() {
        val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(
                Settings.EXTRA_CHANNEL_ID,
                NotificationChannelManager.ALERT_CHANNEL_ID
            )
        runCatching { startActivity(channelIntent) }
            .recoverCatching {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
            .onFailure {
                showGlassNotice("当前系统没有可用的通知渠道设置入口", long = true)
            }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1107)
    }

    private fun knownStatus(status: Bundle, knownKey: String, valueKey: String, invert: Boolean = false): Pair<String, Boolean> {
        if (!status.boolean(knownKey)) return "普通 APK 无权确认 · 请用 dumpsys 验证" to false
        val value = status.boolean(valueKey)
        val good = if (invert) !value else value
        return yesNo(good) to good
    }

    private fun readAdbVpnVerification(status: Bundle): AdbVpnVerification? {
        val verifiedElapsed = status.long(LuonnotarPreferences.KEY_ADB_VERIFIED_ELAPSED)
        val verifiedBootId = status.string(LuonnotarPreferences.KEY_ADB_VERIFIED_BOOT_ID)
        val activePackage = status.string(LuonnotarPreferences.KEY_ADB_ACTIVE_VPN_PACKAGE)
        val evidenceHash = status.string(LuonnotarPreferences.KEY_ADB_EVIDENCE_HASH)
        val verifiedNetworkHandle = status.long(LuonnotarPreferences.KEY_ADB_NETWORK_HANDLE, -1)
        val currentNetworkHandle = status.long(LuonnotarPreferences.KEY_NETWORK_HANDLE, -1)
        if (!AdbVpnEvidencePolicy.isCurrent(
                verifiedElapsed = verifiedElapsed,
                nowElapsed = SystemClock.elapsedRealtime(),
                verifiedBootId = verifiedBootId,
                currentBootId = currentBootId(),
                activePackage = activePackage,
                evidenceHash = evidenceHash,
                verifiedNetworkHandle = verifiedNetworkHandle,
                currentNetworkHandle = currentNetworkHandle,
                vpnPresent = status.boolean(LuonnotarPreferences.KEY_VPN)
            )
        ) {
            if (verifiedElapsed > 0) GuardianStatusClient.clearAdbEvidence(this)
            return null
        }
        return AdbVpnVerification(
            activePackage = activePackage,
            alwaysOn = status.boolean(LuonnotarPreferences.KEY_ADB_ALWAYS_ON),
            lockdown = status.boolean(LuonnotarPreferences.KEY_ADB_LOCKDOWN),
            bypassable = status.boolean(LuonnotarPreferences.KEY_ADB_BYPASSABLE, true),
            gmsRouted = status.boolean(LuonnotarPreferences.KEY_ADB_GMS_ROUTED),
            whatsappRouted = status.boolean(LuonnotarPreferences.KEY_ADB_WHATSAPP_ROUTED),
            whatsappBusinessRouted = status.boolean(
                LuonnotarPreferences.KEY_ADB_WHATSAPP_BUSINESS_ROUTED
            ),
            internetRouted = status.boolean(
                LuonnotarPreferences.KEY_ADB_INTERNET_ROUTED,
                false
            ),
            verifiedElapsed = verifiedElapsed,
            evidenceHash = evidenceHash
        )
    }

    private fun verificationAge(verification: AdbVpnVerification): String {
        val minutes = ((SystemClock.elapsedRealtime() - verification.verifiedElapsed) / 60_000)
            .coerceAtLeast(0)
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            else -> "${minutes / 60}小时前"
        }
    }

    private fun currentBootId(): String = bootId

    private fun readGuardianStatus(): Bundle {
        return GuardianStatusClient.status(this) ?: Bundle.EMPTY
    }

    private fun Bundle.boolean(key: String, default: Boolean = false): Boolean =
        if (containsKey(key)) getBoolean(key) else default

    private fun Bundle.long(key: String, default: Long = 0L): Long =
        if (containsKey(key)) getLong(key) else default

    private fun Bundle.integer(key: String, default: Int = 0): Int =
        if (containsKey(key)) getInt(key) else default

    private fun Bundle.string(key: String, default: String = ""): String =
        if (containsKey(key)) getString(key).orEmpty() else default

    private fun isInstalled(target: String): Boolean = try {
        packageManager.getApplicationInfo(target, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun environmentEvidence(): Pair<List<SupportedVpnProvider>, FcmHealthEvidence> {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedFcmEvidence
        if (
            cached == null ||
            lastEnvironmentInspectionElapsed == Long.MIN_VALUE ||
            now - lastEnvironmentInspectionElapsed >= ENVIRONMENT_INSPECTION_INTERVAL_MS
        ) {
            cachedVpnProviders = SupportedVpnProvider.entries.filter {
                isInstalled(it.packageName)
            }
            cachedFcmEvidence = FcmHealthMonitor(this).inspect()
            lastEnvironmentInspectionElapsed = now
        }
        return cachedVpnProviders to requireNotNull(cachedFcmEvidence)
    }

    private fun showGlassNotice(message: CharSequence, long: Boolean = false) {
        if (!::rootContainer.isInitialized || !::visualBackground.isInitialized) return
        transientNotice?.let { previous ->
            previous.animate().cancel()
            (previous.parent as? ViewGroup)?.removeView(previous)
        }
        val messageView = TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(palette().foreground)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            maxLines = 4
            setLineSpacing(0f, 1.12f)
        }
        val notice = LiquidGlassPanel(
            this,
            dp(18).toFloat(),
            imageContrast = usesImageContrast(),
            enableBackdropBlur = true
        ).apply {
            addView(
                messageView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            bindBackground(visualBackground)
            alpha = 0f
            translationY = dp(24).toFloat()
            scaleX = 0.97f
            scaleY = 0.97f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = message
        }
        val navigationInset = ViewCompat.getRootWindowInsets(rootContainer)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())
            ?.bottom ?: dp(24)
        rootContainer.addView(
            notice,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                marginStart = dp(22)
                marginEnd = dp(22)
                bottomMargin = navigationInset + dp(18)
            }
        )
        transientNotice = notice
        notice.post {
            notice.refreshBackdrop()
            notice.announceForAccessibility(message)
            notice.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300L)
                .setInterpolator(PathInterpolator(0.16f, 1f, 0.3f, 1f))
                .start()
        }
        mainHandler.postDelayed({
            if (transientNotice !== notice) return@postDelayed
            notice.animate()
                .alpha(0f)
                .translationY(dp(18).toFloat())
                .scaleX(0.98f)
                .scaleY(0.98f)
                .setDuration(210L)
                .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
                .withEndAction {
                    if (transientNotice === notice) transientNotice = null
                    (notice.parent as? ViewGroup)?.removeView(notice)
                }
                .start()
        }, if (long) 4_000L else 2_400L)
    }

    private fun actionButton(label: String, action: (View) -> Unit) = GuardianActionButton(this).apply {
        text = label
        isAllCaps = false
        textSize = if (tabletLayout) 18f else 15f
        setTextColor(palette().foreground)
        background = actionBackground()
        setOnClickListener { action(it) }
        minHeight = dp(if (tabletLayout) 60 else 50)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(1)
            marginEnd = dp(1)
            bottomMargin = dp(9)
        }
        val sequence = entranceSequence++
        animateEntrance(this, 120L + sequence.coerceAtMost(10) * 32L)
    }

    private fun primaryGuardianButton(label: String, action: (View) -> Unit) =
        actionButton(label, action).apply {
            textSize = if (tabletLayout) 22f else 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.02f
            minHeight = dp(if (tabletLayout) 82 else 68)
            setPadding(
                dp(if (tabletLayout) 28 else 22),
                dp(if (tabletLayout) 16 else 12),
                dp(if (tabletLayout) 28 else 22),
                dp(if (tabletLayout) 16 else 12)
            )
            (layoutParams as? LinearLayout.LayoutParams)?.apply {
                bottomMargin = dp(8)
            }
            isSelected = true
            contentDescription = "开启极限保活，核心操作"
        }

    private fun sectionTitle(label: String) = TextView(this).apply {
        text = label
        textSize = if (tabletLayout) 23f else 18f
        setTextColor(palette().foreground)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(
            dp(2),
            dp(if (tabletLayout) 30 else 22),
            0,
            dp(if (tabletLayout) 14 else 10)
        )
        animateEntrance(this, 100L + entranceSequence.coerceAtMost(10) * 32L)
    }

    private fun glassPanel(content: View, radiusDp: Int, good: Boolean) =
        LiquidGlassPanel(
            this,
            dp(radiusDp).toFloat(),
            imageContrast = usesImageContrast(),
            enableBackdropBlur = true
        ).apply {
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            setGood(good)
            setTouchFeedbackEnabled(true)
            glassPanels += this
        }

    private fun roundedBackground(color: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(16).toFloat()
        }

    private fun familyCardBackground() =
        GradientDrawable().apply {
            val palette = palette()
            setColor(palette.card)
            setStroke(dp(1), palette.cardStroke)
            cornerRadius = dp(28).toFloat()
        }

    private fun statusCardBackground(good: Boolean) =
        GradientDrawable().apply {
            val palette = palette()
            setColor(if (good) palette.goodCard else palette.neutralCard)
            setStroke(dp(1), if (good) palette.goodStroke else palette.neutralStroke)
            cornerRadius = dp(20).toFloat()
        }

    private fun actionBackground() =
        LiquidGlassDrawable(this, dp(18).toFloat(), usesImageContrast())

    private fun componentIntent(packageName: String, className: String) =
        Intent().setComponent(ComponentName(packageName, className))

    private fun yesNo(value: Boolean) = if (value) "是" else "否"
    private fun formatWallClock(wallTimeMs: Long): String =
        android.text.format.DateFormat.format("MM-dd HH:mm:ss", wallTimeMs).toString()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun palette(): Palette {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val imageContrast = usesImageContrast()
        return if (dark) {
            Palette(
                foreground = 0xFFF5F5F7.toInt(),
                secondary = 0xFFA1A1A6.toInt(),
                good = 0xFF30D158.toInt(),
                warning = 0xFFFFD60A.toInt(),
                card = 0x40030509,
                cardStroke = 0x30FFFFFF,
                goodCard = 0x40030509,
                goodStroke = 0x3030D158,
                neutralCard = 0x40030509,
                neutralStroke = 0x30FFFFFF,
                action = 0x40030509,
                actionStroke = 0x30FFFFFF
            )
        } else if (imageContrast) {
            Palette(
                foreground = 0xFFF7F7F9.toInt(),
                secondary = 0xFFE8E8ED.toInt(),
                good = 0xFF5BE38D.toInt(),
                warning = 0xFFFFD60A.toInt(),
                card = 0xA612161C.toInt(),
                cardStroke = 0x30FFFFFF,
                goodCard = 0xA612161C.toInt(),
                goodStroke = 0x3030D158,
                neutralCard = 0xA612161C.toInt(),
                neutralStroke = 0x30FFFFFF,
                action = 0xA612161C.toInt(),
                actionStroke = 0x30FFFFFF
            )
        } else {
            Palette(
                foreground = 0xFF142128.toInt(),
                secondary = 0xFF465B66.toInt(),
                good = 0xFF177754.toInt(),
                warning = 0xFF9A6200.toInt(),
                card = 0xF2F5F5F7.toInt(),
                cardStroke = 0x38000000,
                goodCard = 0xF2F5F5F7.toInt(),
                goodStroke = 0x38177754,
                neutralCard = 0xF2F5F5F7.toInt(),
                neutralStroke = 0x38000000,
                action = 0xF2F5F5F7.toInt(),
                actionStroke = 0x38000000
            )
        }
    }

    private fun usesImageContrast(): Boolean {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        return !dark && VisualPreferences.load(this).background != BackgroundPreference.SOLID
    }

    private fun themeLabel(theme: ThemePreference): String = when (theme) {
        ThemePreference.DARK -> "深色"
        ThemePreference.LIGHT -> "浅色"
        ThemePreference.SYSTEM -> "跟随系统"
    }

    private fun backgroundLabel(preferences: VisualPreferences): String = when (preferences.background) {
        BackgroundPreference.SOLID -> "纯色"
        BackgroundPreference.SHAO_OU -> "少偶"
        BackgroundPreference.CUSTOM_IMAGE ->
            "自定义图片 · ${if (preferences.backgroundScale == BackgroundScale.FILL_CROP) "填充裁切" else "完整显示"}"
    }

    private fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    private fun animateEntrance(view: View, delayMs: Long) {
        if (!animationsEnabled()) return
        view.alpha = 0f
        view.translationY = dp(12).toFloat()
        view.post {
            if (!view.isAttachedToWindow || !animationsEnabled()) {
                view.alpha = 1f
                view.translationY = 0f
                return@post
            }
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(380L)
                .setInterpolator(PathInterpolator(0.16f, 1f, 0.3f, 1f))
                .start()
        }
    }

    private data class StatusViews(
        val row: View,
        val value: TextView
    )

    private data class PendingBackgroundImport(
        val theme: ThemePreference,
        val scale: BackgroundScale
    )

    private data class AdbVpnVerification(
        val activePackage: String,
        val alwaysOn: Boolean,
        val lockdown: Boolean,
        val bypassable: Boolean,
        val gmsRouted: Boolean,
        val whatsappRouted: Boolean,
        val whatsappBusinessRouted: Boolean,
        val internetRouted: Boolean,
        val verifiedElapsed: Long,
        val evidenceHash: String
    )

    private data class Palette(
        val foreground: Int,
        val secondary: Int,
        val good: Int,
        val warning: Int,
        val card: Int,
        val cardStroke: Int,
        val goodCard: Int,
        val goodStroke: Int,
        val neutralCard: Int,
        val neutralStroke: Int,
        val action: Int,
        val actionStroke: Int
    )

    companion object {
        const val EXTRA_STATUS_MESSAGE = "status_message"
        const val EXTRA_OPEN_VPN_CHOOSER = "open_vpn_chooser"
        const val VISIBLE_RECOVERY_COOLDOWN_MS = 15_000L
        const val KEEPALIVE_SUCCESS_FRESH_MS = 10 * 60_000L
        const val ENVIRONMENT_INSPECTION_INTERVAL_MS = 30_000L
    }
}
