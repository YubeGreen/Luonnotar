package com.yubegreen.luonnotar

import android.Manifest
import android.app.NotificationManager
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.ActivityNotFoundException
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
import android.os.PowerManager
import android.os.Process
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
import org.json.JSONObject
import com.yubegreen.luonnotar.monitor.FcmHealthEvidence
import com.yubegreen.luonnotar.monitor.FcmHealthMonitor
import com.yubegreen.luonnotar.monitor.AdbVpnEvidencePolicy
import com.yubegreen.luonnotar.monitor.GuardianState
import com.yubegreen.luonnotar.monitor.SupportedVpnProvider
import com.yubegreen.luonnotar.monitor.TargetRoutingPolicy
import com.yubegreen.luonnotar.monitor.TargetRoutingSnapshot
import com.yubegreen.luonnotar.monitor.TargetUidHealthSnapshot
import com.yubegreen.luonnotar.monitor.VpnRouteState
import com.yubegreen.luonnotar.notification.NotificationChannelManager
import com.yubegreen.luonnotar.notification.ControlledPushDeliveryState
import com.yubegreen.luonnotar.notification.PushTestDeliveryPolicy
import com.yubegreen.luonnotar.notification.GmsBinderAnchorState
import com.yubegreen.luonnotar.notification.RecoveryNotificationAvailability
import com.yubegreen.luonnotar.policy.PolicyActivity
import com.yubegreen.luonnotar.policy.PolicyGate
import com.yubegreen.luonnotar.privileged.BackgroundPolicyVendorFamily
import com.yubegreen.luonnotar.privileged.PrivilegedGuardianController
import com.yubegreen.luonnotar.privileged.PrivilegedGuardianSnapshot
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedBackgroundPolicyStore
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianManager
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedRebootAlertGuidePolicy
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedRebootAlertGuideStore
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianNotifier
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianStatePolicy
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianStore
import com.yubegreen.luonnotar.privileged.embedded.OemBackgroundSettingsNavigator
import com.yubegreen.luonnotar.service.FcmGuardianService
import com.yubegreen.luonnotar.service.GuardianLiveness
import com.yubegreen.luonnotar.service.GuardianProfilePolicy
import com.yubegreen.luonnotar.service.GuardianRuntimeProfile
import com.yubegreen.luonnotar.service.GuardianStatusClient
import com.yubegreen.luonnotar.ui.motion.ElasticNestedScrollView
import com.yubegreen.luonnotar.ui.motion.GuardianActionButton
import com.yubegreen.luonnotar.ui.visual.BackgroundImageStore
import com.yubegreen.luonnotar.ui.visual.AdaptiveLayout
import com.yubegreen.luonnotar.ui.visual.AdaptiveMaxWidthLinearLayout
import com.yubegreen.luonnotar.ui.visual.BackgroundPreference
import com.yubegreen.luonnotar.ui.visual.BackgroundScale
import com.yubegreen.luonnotar.ui.visual.GlassMessageDialog
import com.yubegreen.luonnotar.ui.visual.GuardianExperimentDialog
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
    private lateinit var detailedStatusButton: Button
    private lateinit var serviceButton: Button
    private lateinit var embeddedGuardianSummary: TextView
    private lateinit var embeddedGuardianSectionAnchor: View
    private lateinit var embeddedGuardianSetupButton: Button
    private lateinit var embeddedGuardianStopButton: Button
    private lateinit var embeddedGuardianCycleButton: Button
    private lateinit var embeddedGuardianGmsButton: Button
    private lateinit var embeddedBackgroundPolicySummary: TextView
    private lateinit var embeddedBackgroundPolicyApplyButton: Button
    private lateinit var embeddedBackgroundPolicySettingsButton: Button
    private lateinit var embeddedRebootAlertSummary: TextView
    private lateinit var embeddedRebootAlertSetupButton: Button
    private lateinit var embeddedRebootAlertTestButton: Button
    private lateinit var privilegedGuardianSummary: TextView
    private lateinit var privilegedGuardianPermissionButton: Button
    private lateinit var privilegedGuardianToggleButton: Button
    private lateinit var privilegedGuardianCycleButton: Button
    private lateinit var privilegedGmsRecoverySummary: TextView
    private lateinit var privilegedGmsRecoveryToggleButton: Button
    private lateinit var privilegedGmsRecoveryNowButton: Button
    private lateinit var aggressiveModeButton: Button
    private lateinit var cpuGuardSummary: TextView
    private lateinit var cpuGuardButton: Button
    private lateinit var wifiLockButton: Button
    private lateinit var periodicDnsButton: Button
    private lateinit var periodicHttpsButton: Button
    private lateinit var serviceActionHint: TextView
    private lateinit var batteryOptimizationButton: Button
    private lateinit var gmsAnchorSummary: TextView
    private lateinit var gmsAnchorButton: Button
    private lateinit var gmsAnchorRetryButton: Button
    private lateinit var gmsPulseTestButton: Button
    private lateinit var scrollView: ElasticNestedScrollView
    private lateinit var rootContainer: FrameLayout
    private lateinit var visualBackground: VisualBackgroundView
    private lateinit var visualSummary: TextView
    private lateinit var topSystemBarFade: View
    private lateinit var bottomSystemBarFade: View
    private val glassPanels = mutableListOf<LiquidGlassPanel>()
    private val glassButtons = mutableListOf<GuardianActionButton>()
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
    private val statusDetails = linkedMapOf<String, StatusDetail>()
    private var entranceSequence = 0
    private var pendingRestoredScrollY: Int? = null
    private var scrollRestorationPosted = false
    private val tabletLayout by lazy { AdaptiveLayout.isTablet(this) }
    private val twoColumnStatus by lazy { AdaptiveLayout.isWideTablet(this) }
    private val backgroundPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::importBackground) ?: run { pendingBackgroundImport = null }
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        handleEmbeddedRebootNotificationPermissionResult(granted)
    }
    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            restorePendingScrollAfterStatusRows()
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
        pendingRestoredScrollY = ThemeTransitionSnapshot.takeScrollY()
        if (pendingRestoredScrollY == null) {
            scrollView.post {
                scrollView.scrollTo(0, 0)
                finishThemeTransition()
                consumeStatusMessage(intent)
            }
        } else {
            scrollView.post {
                scrollView.scrollTo(0, pendingRestoredScrollY ?: 0)
            }
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
        if (intent?.getBooleanExtra(EXTRA_SCROLL_TO_EMBEDDED_GUARDIAN, false) == true) {
            val source = intent.getStringExtra(EXTRA_EMBEDDED_NOTIFICATION_SOURCE)
                .orEmpty().ifBlank { "reboot_reminder_content" }
            val bootAction = intent.getStringExtra(EXTRA_EMBEDDED_NOTIFICATION_BOOT_ACTION)
                .orEmpty()
            intent.removeExtra(EXTRA_SCROLL_TO_EMBEDDED_GUARDIAN)
            intent.removeExtra(EXTRA_EMBEDDED_NOTIFICATION_SOURCE)
            intent.removeExtra(EXTRA_EMBEDDED_NOTIFICATION_BOOT_ACTION)
            LogManager.event(
                this,
                "embedded_notification_content_clicked",
                EmbeddedGuardianNotifier.eventFields(this, source, bootAction)
            )
            if (::embeddedGuardianSectionAnchor.isInitialized) {
                scrollView.smoothScrollTo(0, embeddedGuardianSectionAnchor.top)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        recoverStaleGuardianFromVisibleActivity()
        val status = readGuardianStatus()
        if (
            status.boolean(LuonnotarPreferences.KEY_ENABLED) &&
            !status.boolean(LuonnotarPreferences.KEY_PAUSED) &&
            guardianProfile(status) != GuardianRuntimeProfile.ADB_PASSIVE
        ) {
            GuardianStatusClient.scheduleRecoveryAlarm(this)
            FcmRecoveryWorker.ensurePeriodic(this)
        }
        updateBatteryOptimizationButton()
        consumeEmbeddedRebootAlertSettingsReturn()
        mainHandler.post(refresh)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun restorePendingScrollAfterStatusRows() {
        val targetScrollY = pendingRestoredScrollY ?: return
        if (scrollRestorationPosted) return
        scrollRestorationPosted = true
        scrollView.post {
            scrollView.scrollTo(0, targetScrollY)
            scrollView.postOnAnimation {
                scrollView.scrollTo(0, targetScrollY)
                pendingRestoredScrollY = null
                scrollRestorationPosted = false
                finishThemeTransition()
                consumeStatusMessage(intent)
            }
        }
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
                text = "●  Privileged Guardian ${BuildConfig.VERSION_NAME}"
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

        embeddedGuardianSectionAnchor = sectionTitle("2.1 内置特权引擎")
        root.addView(embeddedGuardianSectionAnchor)
        embeddedGuardianSummary = TextView(this).apply {
            textSize = if (tabletLayout) 16f else 13f
            setTextColor(palette.secondary)
            setPadding(dp(4), 0, dp(4), dp(10))
            text = "正在读取内置 shell 特权引擎…"
        }
        root.addView(embeddedGuardianSummary)
        embeddedGuardianSetupButton = actionButton("配对 / 启动内置特权引擎") {
            startEmbeddedGuardianSetup()
        }
        root.addView(embeddedGuardianSetupButton)
        embeddedGuardianStopButton = actionButton("停止内置特权引擎") {
            stopEmbeddedGuardian()
        }
        root.addView(embeddedGuardianStopButton)
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
        detailedStatusButton = actionButton("查看详细诊断状态") {
            showDetailedStatusDialog(detailedStatusButton)
        }
        root.addView(detailedStatusButton)

        root.addView(actionButton("重启守护服务") { restartGuardian() })
        root.addView(actionButton("执行 VPN-only 连通性检测") { manualCheck() })
        aggressiveModeButton = actionButton("守护功能开关") {
            showGuardianExperimentDialog(aggressiveModeButton)
        }
        root.addView(aggressiveModeButton)

        root.addView(sectionTitle("内置特权引擎高级操作"))
        embeddedRebootAlertSummary = TextView(this).apply {
            textSize = if (tabletLayout) 15f else 12f
            setTextColor(palette.secondary)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            text = "正在检查重启横幅提醒…"
        }
        root.addView(embeddedRebootAlertSummary)
        embeddedRebootAlertSetupButton = actionButton("配置重启横幅提醒") {
            beginEmbeddedRebootAlertGuide(continueEmbeddedSetup = false)
        }
        root.addView(embeddedRebootAlertSetupButton)
        embeddedRebootAlertTestButton = actionButton("发送测试横幅") {
            sendEmbeddedRebootAlertTest("manual_button")
        }
        root.addView(embeddedRebootAlertTestButton)
        embeddedGuardianCycleButton = actionButton("内置引擎立即执行守护周期") {
            runEmbeddedGuardianCycle()
        }
        root.addView(embeddedGuardianCycleButton)
        embeddedGuardianGmsButton = actionButton("提交 GMS 深度恢复（异步验证）") {
            runEmbeddedGuardianGmsRecovery()
        }
        root.addView(embeddedGuardianGmsButton)
        embeddedBackgroundPolicySummary = TextView(this).apply {
            textSize = if (tabletLayout) 15f else 12f
            setTextColor(palette.secondary)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            text = "正在读取厂商后台白名单状态…"
        }
        root.addView(embeddedBackgroundPolicySummary)
        embeddedBackgroundPolicyApplyButton = actionButton("检测厂商并修复 ADB 白名单") {
            runEmbeddedBackgroundPolicyRepair()
        }
        root.addView(embeddedBackgroundPolicyApplyButton)
        embeddedBackgroundPolicySettingsButton = actionButton("打开厂商后台设置") {
            openOemBackgroundSettings()
        }
        root.addView(embeddedBackgroundPolicySettingsButton)
        root.addView(TextView(this).apply {
            text =
                "首次及每次重启后，打开系统无线调试；努昂诺塔会自动发现端口，并可直接在通知栏输入 6 位配对码。" +
                    "启动成功后，shell UID 进程独立于努昂诺塔应用 UID。"
            textSize = if (tabletLayout) 15f else 12f
            setTextColor(palette.secondary)
            setPadding(dp(4), dp(2), dp(4), dp(12))
        })



        root.addView(sectionTitle("Shizuku / Sui 兼容后备"))
        privilegedGuardianSummary = TextView(this).apply {
            textSize = if (tabletLayout) 16f else 13f
            setTextColor(palette.secondary)
            setPadding(dp(4), 0, dp(4), dp(10))
            text = "正在读取 Shizuku / Sui 特权引擎…"
        }
        root.addView(privilegedGuardianSummary)
        privilegedGuardianPermissionButton = actionButton("连接 Shizuku / Sui") {
            requestPrivilegedGuardianPermission()
        }
        root.addView(privilegedGuardianPermissionButton)
        privilegedGuardianToggleButton = actionButton("开启 Privileged Guardian") {
            togglePrivilegedGuardian()
        }
        root.addView(privilegedGuardianToggleButton)
        privilegedGuardianCycleButton = actionButton("立即执行特权守护周期") {
            runPrivilegedGuardianCycle()
        }
        root.addView(privilegedGuardianCycleButton)
        privilegedGmsRecoverySummary = TextView(this).apply {
            textSize = if (tabletLayout) 15f else 12f
            setTextColor(palette.secondary)
            setPadding(dp(4), dp(4), dp(4), dp(8))
            text = "正在读取 GMS 深度恢复状态…"
        }
        root.addView(privilegedGmsRecoverySummary)
        privilegedGmsRecoveryToggleButton = actionButton("开启自动 GMS 深度恢复") {
            togglePrivilegedGmsRecovery()
        }
        root.addView(privilegedGmsRecoveryToggleButton)
        privilegedGmsRecoveryNowButton = actionButton("立即重启 GMS 并验证 PID") {
            runPrivilegedGmsRecoveryNow()
        }
        root.addView(privilegedGmsRecoveryNowButton)
        root.addView(TextView(this).apply {
            text =
                "该引擎运行在 shell/root UID 的独立 UserService 中，不依赖努昂诺塔主进程或 :keeper 存活。" +
                    "自动 GMS 深度恢复默认关闭；开启后仅在 10 分钟内出现至少 3 次独立 GMS 冻结证据时，" +
                    "才会 force-stop GMS，并执行 6 小时冷却与每日 2 次上限。"
            textSize = if (tabletLayout) 15f else 12f
            setTextColor(palette.secondary)
            setPadding(dp(4), dp(2), dp(4), dp(12))
        })

        root.addView(sectionTitle("可靠性控制"))
        cpuGuardSummary = TextView(this).apply {
            textSize = if (tabletLayout) 16f else 13f
            setTextColor(palette.secondary)
            setPadding(dp(4), 0, dp(4), dp(8))
            text = "正在读取熄屏 CPU Guard 状态…"
        }
        root.addView(cpuGuardSummary)
        cpuGuardButton = actionButton("开启熄屏 CPU Guard") {
            toggleScreenOffCpuGuard()
        }
        root.addView(cpuGuardButton)
        root.addView(TextView(this).apply {
            text =
                "守护默认启用：只在守护运行且屏幕关闭时保持 CPU 醒着；" +
                    "亮屏立即释放。其他功能可独立开关。"
            textSize = if (tabletLayout) 15f else 12f
            setTextColor(palette.secondary)
            setPadding(dp(4), dp(2), dp(4), dp(12))
        })

        wifiLockButton = actionButton("开启高性能 Wi-Fi Lock") {
            toggleGuardianExperiment(
                LuonnotarPreferences.KEY_EXPERIMENT_HIGH_PERF_WIFI_LOCK,
                "高性能 Wi-Fi Lock"
            )
        }
        root.addView(wifiLockButton)

        periodicDnsButton = actionButton("开启周期 VPN DNS 检测") {
            toggleGuardianExperiment(
                LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_DNS,
                "周期 VPN DNS 检测"
            )
        }
        root.addView(periodicDnsButton)

        periodicHttpsButton = actionButton("开启周期 HTTPS 204 检测") {
            toggleGuardianExperiment(
                LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_HTTPS,
                "周期 HTTPS 204 检测"
            )
        }
        root.addView(periodicHttpsButton)

        gmsAnchorSummary = TextView(this).apply {
            textSize = if (tabletLayout) 16f else 13f
            setTextColor(palette.secondary)
            setPadding(dp(4), dp(10), dp(4), dp(8))
            text = "GMS Binder Anchor：关闭 · 不影响正常守护"
        }
        root.addView(gmsAnchorSummary)
        gmsAnchorButton = actionButton("开启 GMS Binder Anchor") {
            toggleGmsBinderAnchor()
        }
        root.addView(gmsAnchorButton)
        gmsAnchorRetryButton = actionButton("重新连接 GMS Binder Anchor") {
            requestGmsBinderAnchorRetry()
        }
        root.addView(gmsAnchorRetryButton)
        gmsPulseTestButton = actionButton("GMS Binder 脉冲测试（15 秒）") {
            requestGmsBinderPulseTest()
        }
        root.addView(gmsPulseTestButton)
        root.addView(TextView(this).apply {
            text =
                "实验性进程依赖方案。Anchor 由 :keeper 前台守护进程持续持有；" +
                    "脉冲测试会每 2 秒建立连接、执行一次只读位置设置查询后解绑，" +
                    "持续 15 秒，不处理 WhatsApp，也不发送 GCM 广播。"
            textSize = if (tabletLayout) 15f else 12f
            setTextColor(palette.secondary)
            setPadding(dp(4), dp(2), dp(4), dp(12))
        })

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
        root.addView(actionButton("厂商后台限制与无 ADB 向导") { showOemGuide(it) })
        batteryOptimizationButton = actionButton("请求努昂诺塔不受电池优化") {
            requestOwnBatteryOptimizationExemption()
        }
        root.addView(batteryOptimizationButton)
        root.addView(actionButton("机型专用 ADB 稳定性与路由证据") { showAdvancedGuide(it) })

        root.addView(sectionTitle("诊断与隐私"))
        root.addView(actionButton("一键导出诊断包") { exportLogs() })
        root.addView(actionButton("通知到达验证模式") { enableNotificationEvidence(it) })
        root.addView(actionButton("守护恢复精确闹钟设置") { openExactAlarmSettings() })
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
                if (::visualBackground.isInitialized) {
                    visualBackground.invalidateSurfacePositions()
                }
            }
            setOnScrollChangeListener { _, _, _, _, _ ->
                if (::visualBackground.isInitialized) {
                    visualBackground.invalidateSurfacePositions()
                }
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
        glassButtons.forEach { it.bindGlassBackground(visualBackground) }
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
        if (usesImageContrast()) applyImageBackgroundTextContrast(root)
        rootContainer.post { visualBackground.invalidateSurfacePositions() }
        return rootContainer
    }

    private fun configureSystemBars() {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val imageContrast = usesImageContrast()
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
        val imageContrast = usesImageContrast()
        val edge = when {
            imageContrast -> 0x52000000
            dark -> 0x7007090D
            else -> 0x4CF6F8FC
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
        updatePrivilegedGuardianUi()
        val status = readGuardianStatus()
        updateGmsAnchorUi(status)
        updateGuardianFeatureButtons(status)
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
                nowUptime = SystemClock.uptimeMillis(),
                lastTickUptime = status.long(LuonnotarPreferences.KEY_LAST_TICK_UPTIME),
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
        val nativeRouteState = runCatching {
            VpnRouteState.valueOf(
                status.string(
                    LuonnotarPreferences.KEY_VPN_ROUTE_STATE,
                    VpnRouteState.UNKNOWN.name
                )
            )
        }.getOrDefault(VpnRouteState.UNKNOWN)
        val nativeIpv4Default =
            status.boolean(LuonnotarPreferences.KEY_VPN_IPV4_DEFAULT_ROUTE)
        val nativeIpv6Default =
            status.boolean(LuonnotarPreferences.KEY_VPN_IPV6_DEFAULT_ROUTE)
        val adbVerification = readAdbVpnVerification(status)
        val activeProvider = adbVerification?.activePackage
            ?.let(SupportedVpnProvider::fromPackage)
        val targetRoutingVerified = adbVerification?.let {
            !it.stale &&
            activeProvider != null &&
                TargetRoutingPolicy.isVerified(
                    TargetRoutingSnapshot(true, true, it.internetRouted),
                    TargetRoutingSnapshot(
                        status.boolean(LuonnotarPreferences.KEY_MONITOR_GMS, true),
                        isActiveUserTarget("com.google.android.gms"),
                        it.gmsRouted
                    ),
                    TargetRoutingSnapshot(
                        status.boolean(LuonnotarPreferences.KEY_MONITOR_WHATSAPP, true),
                        isActiveUserTarget("com.whatsapp"),
                        it.whatsappRouted
                    ),
                    TargetRoutingSnapshot(
                        status.boolean(LuonnotarPreferences.KEY_MONITOR_WHATSAPP_BUSINESS, false),
                        isActiveUserTarget("com.whatsapp.w4b"),
                        it.whatsappBusinessRouted
                    )
                )
        } == true
        val bypassableText = adbVerification?.let {
                val good =
                    !it.stale && activeProvider != null && !it.bypassable
                "${yesNo(good)} · ADB 导入的目标 UID 证据" to good
            } ?: knownStatus(
                status,
                LuonnotarPreferences.KEY_BYPASSABLE_KNOWN,
                LuonnotarPreferences.KEY_BYPASSABLE,
                invert = true
            )
        val alwaysOn = adbVerification?.let {
            val good = !it.stale && it.alwaysOn && activeProvider != null
            "${yesNo(good)} · ADB 导入 ${verificationAge(it)}" to good
        } ?: knownStatus(
            status,
            LuonnotarPreferences.KEY_ALWAYS_ON_KNOWN,
            LuonnotarPreferences.KEY_ALWAYS_ON
        )
        val lockdown = adbVerification?.let {
            if (it.stale) {
                "STALE · 会话绑定仍有效，但需重新核验目标 UID" to false
            } else if (it.lockdown) {
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
        val successSessionMatches =
            status.string(
                LuonnotarPreferences.KEY_LAST_SUCCESS_SESSION_FINGERPRINT
            ).isNotBlank() &&
                status.string(
                    LuonnotarPreferences.KEY_LAST_SUCCESS_SESSION_FINGERPRINT
                ) ==
                status.string(
                    LuonnotarPreferences.KEY_VPN_SESSION_FINGERPRINT
                )
        val attemptSessionMatches =
            status.string(
                LuonnotarPreferences.KEY_LAST_ATTEMPT_SESSION_FINGERPRINT
            ).isNotBlank() &&
                status.string(
                    LuonnotarPreferences.KEY_LAST_ATTEMPT_SESSION_FINGERPRINT
                ) ==
                status.string(
                    LuonnotarPreferences.KEY_VPN_SESSION_FINGERPRINT
                )
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
            successNetworkMatches &&
            successSessionMatches

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
        val tailscalePresent =
            status.boolean(LuonnotarPreferences.KEY_TAILSCALE_PRESENT)
        val tailscaleComplete =
            status.boolean(LuonnotarPreferences.KEY_TAILSCALE_COMPLETE)
        val tailscaleBlockedKnown =
            status.boolean(LuonnotarPreferences.KEY_TAILSCALE_BLOCKED_KNOWN)
        val tailscaleBlocked =
            status.boolean(LuonnotarPreferences.KEY_TAILSCALE_BLOCKED)
        val tailscaleSuspended =
            status.boolean(LuonnotarPreferences.KEY_TAILSCALE_SUSPENDED)
        val tailscaleSelfExcluded =
            status.boolean(LuonnotarPreferences.KEY_TAILSCALE_SELF_EXCLUDED)
        val tailscaleValidated =
            status.boolean(LuonnotarPreferences.KEY_TAILSCALE_VALIDATED)
        val tailscaleEngineHealthy =
            serviceAlive &&
                tailscalePresent &&
                tailscaleComplete &&
                tailscaleValidated &&
                (!tailscaleBlockedKnown || !tailscaleBlocked) &&
                !tailscaleSuspended &&
                !tailscaleSelfExcluded
        addStatus(
            "Tailscale 引擎",
            when {
                !tailscalePresent -> "未观察到活动 Tailscale VPN"
                tailscaleSelfExcluded ->
                    "努昂诺塔未使用该 VPN handle · 检查应用分流"
                tailscaleBlocked -> "Android 报告 blocked"
                tailscaleSuspended -> "缺少 NOT_SUSPENDED"
                !tailscaleComplete ->
                    "等待同一 handle 的 Capabilities + LinkProperties"
                !tailscaleValidated -> "未通过 VALIDATED"
                else -> "可传输 · 未阻塞 · NOT_SUSPENDED"
            },
            tailscaleEngineHealthy
        )
        val tailscaleDnsFailures =
            status.integer(LuonnotarPreferences.KEY_TAILSCALE_DNS_FAILURES)
        val tailscaleDnsRtt =
            status.long(
                LuonnotarPreferences.KEY_TAILSCALE_DNS_LAST_RTT,
                -1L
            )
        val tailscaleDnsSuccess =
            status.long(
                LuonnotarPreferences.KEY_TAILSCALE_DNS_LAST_SUCCESS_ELAPSED
            )
        val tailscaleDnsCurrent =
            status.long(
                LuonnotarPreferences.KEY_TAILSCALE_DNS_EVIDENCE_GENERATION,
                -1L
            ) ==
                status.long(
                    LuonnotarPreferences.KEY_SERVICE_GENERATION,
                    -2L
                ) &&
                status.long(
                    LuonnotarPreferences.KEY_TAILSCALE_DNS_EVIDENCE_NETWORK_HANDLE,
                    -1L
                ) ==
                status.long(LuonnotarPreferences.KEY_NETWORK_HANDLE, -2L)
        val tailscaleDnsHealthy =
            tailscaleDnsCurrent &&
                tailscaleDnsSuccess > 0L &&
                SystemClock.elapsedRealtime() - tailscaleDnsSuccess in
                0..TAILSCALE_DNS_FRESH_MS &&
                tailscaleDnsFailures == 0
        addStatus(
            "Tailscale Quad100 DNS",
            when {
                !tailscalePresent -> "等待 Tailscale"
                tailscaleDnsHealthy ->
                    "绑定 VPN 成功 · ${tailscaleDnsRtt}ms"
                tailscaleDnsFailures > 0 ->
                    "连续失败 $tailscaleDnsFailures · ${
                        status.string(
                            LuonnotarPreferences.KEY_TAILSCALE_DNS_LAST_ERROR,
                            "未知错误"
                        )
                    }"
                else -> "等待绑定 VPN 的 DNS 探测"
            },
            serviceAlive && tailscaleDnsHealthy
        )
        addStatus(
            "VPN 公网默认路由",
            if (nativeRouteState == VpnRouteState.ROUTED) {
                "已检测 · IPv4 ${yesNo(nativeIpv4Default)} / IPv6 ${
                    yesNo(nativeIpv6Default)
                }${
                    if (nativeVpnProvider == SupportedVpnProvider.TAILSCALE) {
                        " · Tailscale Exit Node 路径存在"
                    } else {
                        ""
                    }
                }"
            } else if (nativeRouteState == VpnRouteState.UNKNOWN) {
                "路由证据暂不可用 · 等待完整 LinkProperties"
            } else if (nativeVpnProvider == SupportedVpnProvider.TAILSCALE) {
                "未检测到 0.0.0.0/0 或 ::/0 · 请检查 Exit Node"
            } else {
                "未检测到 VPN 公网默认路由"
            },
            serviceAlive &&
                vpn &&
                validated &&
                nativeRouteState == VpnRouteState.ROUTED &&
                nativeInternetRouted
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
        val wakeLockHeld =
            serviceAlive &&
                status.boolean(LuonnotarPreferences.KEY_WAKE_LOCK)
        val continuousWakeLockHeld =
            serviceAlive &&
                status.boolean(
                    LuonnotarPreferences.KEY_CONTINUOUS_WAKE_LOCK
                )
        val wifiLockHeld =
            serviceAlive &&
                status.boolean(LuonnotarPreferences.KEY_WIFI_LOCK)
        val screenOffCpuGuard =
            status.boolean(
                LuonnotarPreferences
                    .KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD
            )
        val screenInteractive =
            getSystemService(PowerManager::class.java).isInteractive
        val underlyingTransport = status.string(LuonnotarPreferences.KEY_TRANSPORT, "UNKNOWN")
        val vivoFamily = isVivoFamily()
        val runtimeProfile = runCatching {
            GuardianRuntimeProfile.valueOf(
                status.string(
                    LuonnotarPreferences.KEY_GUARDIAN_PROFILE,
                    GuardianProfilePolicy.defaultProfile(vivoFamily).name
                )
            )
        }.getOrDefault(GuardianProfilePolicy.defaultProfile(vivoFamily))
        val labLevel =
            status.integer(LuonnotarPreferences.KEY_LAB_EXTREME_LEVEL, 0)
                .coerceIn(0, 4)
        val scopedCpuLock = status.boolean(
            LuonnotarPreferences.KEY_EXPERIMENT_SCOPED_CPU_LOCK
        )
        val permanentCpuLock = status.boolean(
            LuonnotarPreferences.KEY_EXPERIMENT_PERMANENT_CPU_LOCK
        )
        val highPerfWifiLock = status.boolean(
            LuonnotarPreferences.KEY_EXPERIMENT_HIGH_PERF_WIFI_LOCK
        )
        val periodicDns = status.boolean(
            LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_DNS
        )
        val periodicHttps = status.boolean(
            LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_HTTPS
        )
        addStatus(
            "守护运行策略",
            when {
                runtimeProfile == GuardianRuntimeProfile.IQOO_COOPERATIVE ->
                    "IQOO_COOPERATIVE · 厂商协作策略 · 静默 120 秒"
                runtimeProfile == GuardianRuntimeProfile.LAB_EXTREME ->
                    "LAB_EXTREME L$labLevel · 仅测试机使用"
                runtimeProfile == GuardianRuntimeProfile.ADB_PASSIVE ->
                    "ADB 一次性隐身验证 · 运行 60 秒后自动关闭守护"
                else ->
                    "STANDARD · DNS/HTTPS 分离执行"
            },
            runtimeProfile == GuardianRuntimeProfile.IQOO_COOPERATIVE ||
                !vivoFamily
        )
        val enabledExperiments = listOfNotNull(
            "熄屏 CPU Guard".takeIf { screenOffCpuGuard },
            "永久 CPU".takeIf { permanentCpuLock },
            "高性能 Wi‑Fi".takeIf { highPerfWifiLock },
            "周期 DNS".takeIf { periodicDns },
            "周期 HTTPS".takeIf { periodicHttps },
            "屏幕事件".takeIf {
                status.boolean(
                    LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_EVENT_PROBE
                )
            },
            "自动 mtalk".takeIf {
                status.boolean(
                    LuonnotarPreferences.KEY_EXPERIMENT_AUTOMATIC_MTALK
                )
            },
            "频繁通知".takeIf {
                status.boolean(
                    LuonnotarPreferences
                        .KEY_EXPERIMENT_FREQUENT_NOTIFICATION_REFRESH
                )
            }
        )
        addStatus(
            "守护功能开关",
            enabledExperiments.joinToString(" / ").ifBlank { "全部关闭" },
            runtimeProfile != GuardianRuntimeProfile.IQOO_COOPERATIVE ||
                (
                    screenOffCpuGuard &&
                        scopedCpuLock &&
                        !permanentCpuLock &&
                        !highPerfWifiLock &&
                        !periodicDns &&
                        !periodicHttps
                    )
        )
        addStatus(
            "熄屏 CPU Guard",
            when {
                !screenOffCpuGuard ->
                    "关闭 · 不持有连续 CPU WakeLock"
                !enabled || paused ->
                    "已配置 · 守护当前未运行"
                screenInteractive ->
                    "开启 · 当前亮屏 · 连续锁 ${
                        if (continuousWakeLockHeld) {
                            "异常仍持有"
                        } else {
                            "已释放"
                        }
                    }"
                continuousWakeLockHeld ->
                    "开启 · 当前熄屏 · 连续锁已持有"
                else ->
                    "开启 · 当前熄屏 · 连续锁尚未持有"
            },
            when {
                !screenOffCpuGuard ->
                    !continuousWakeLockHeld
                !enabled || paused || !serviceAlive ->
                    !continuousWakeLockHeld
                screenInteractive ->
                    !continuousWakeLockHeld
                else ->
                    continuousWakeLockHeld
            }
        )
        updateCpuGuardUi(
            status = status,
            serviceAlive = serviceAlive,
            screenInteractive = screenInteractive,
            continuousWakeLockHeld = continuousWakeLockHeld
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
            when {
                permanentCpuLock ->
                    wakeLockHeld &&
                        (!highPerfWifiLock || !wifiRequired || wifiLockHeld)
                screenOffCpuGuard && !screenInteractive ->
                    continuousWakeLockHeld &&
                        (!highPerfWifiLock || !wifiRequired || wifiLockHeld)
                screenOffCpuGuard ->
                    !continuousWakeLockHeld &&
                        (!highPerfWifiLock || !wifiRequired || wifiLockHeld)
                highPerfWifiLock ->
                    !wakeLockHeld && (!wifiRequired || wifiLockHeld)
                else ->
                    !wifiLockHeld &&
                        (!wakeLockHeld || scopedCpuLock)
            }
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
        val targetSnapshots = runCatching {
            TargetUidHealthSnapshot.parseArray(
                status.string(
                    LuonnotarPreferences.KEY_TARGET_UID_HEALTH_SNAPSHOT
                )
            )
        }.getOrDefault(emptyList())
        val targetProblems = targetSnapshots.filter {
            it.installed == com.yubegreen.luonnotar.monitor.DiagnosticTruth.TRUE &&
                (
                    it.frozen == com.yubegreen.luonnotar.monitor.DiagnosticTruth.TRUE ||
                        it.backgroundRestricted == com.yubegreen.luonnotar.monitor.DiagnosticTruth.TRUE ||
                        it.netpolicyBlocked == com.yubegreen.luonnotar.monitor.DiagnosticTruth.TRUE ||
                        it.packageStopped == com.yubegreen.luonnotar.monitor.DiagnosticTruth.TRUE ||
                        it.packageEnabled == com.yubegreen.luonnotar.monitor.DiagnosticTruth.FALSE ||
                        it.packageSuspended == com.yubegreen.luonnotar.monitor.DiagnosticTruth.TRUE ||
                        it.notificationEnabled == com.yubegreen.luonnotar.monitor.DiagnosticTruth.FALSE ||
                        it.postNotificationsAllowed == com.yubegreen.luonnotar.monitor.DiagnosticTruth.FALSE
                    )
        }
        val targetUnknown = targetSnapshots.count {
            it.outputParsed !=
                com.yubegreen.luonnotar.monitor.DiagnosticTruth.TRUE
        }
        addStatus(
            "目标 UID ADB 健康快照",
            when {
                targetSnapshots.isEmpty() ->
                    "未导入 · 运行 tools/test-iqoo-freezer.ps1"
                targetUnknown > 0 ->
                    "$targetUnknown/${targetSnapshots.size} 个目标包含 UNKNOWN；命令失败或 ROM 输出不可解析时不会显示为正常"
                targetProblems.isEmpty() ->
                    "${targetSnapshots.size} 个目标 · 未发现标准项异常（vivo 私有项仍需人工确认）"
                else ->
                    "${targetProblems.size}/${targetSnapshots.size} 个目标存在冻结、进程、后台、网络、stopped 或通知风险：${
                        targetProblems.joinToString { it.packageName }
                    }"
            },
            serviceAlive &&
                targetSnapshots.isNotEmpty() &&
                targetProblems.isEmpty()
        )
        val vpnSessionHealth = status.string(
            LuonnotarPreferences.KEY_VPN_SESSION_HEALTH,
            "UNKNOWN"
        )
        val sessionFingerprint = status.string(
            LuonnotarPreferences.KEY_VPN_SESSION_FINGERPRINT
        )
        addStatus(
            "VPN 会话健康",
            "$vpnSessionHealth · generation ${
                status.long(
                    LuonnotarPreferences.KEY_VPN_SESSION_GENERATION
                )
            } · ${
                sessionFingerprint.take(12).ifBlank { "无 fingerprint" }
            }",
            serviceAlive && vpnSessionHealth == "HEALTHY"
        )
        val vpnDnsHealth = status.string(
            LuonnotarPreferences.KEY_VPN_DNS_HEALTH,
            "UNKNOWN"
        )
        val vpnDnsRtt = status.long(
            LuonnotarPreferences.KEY_VPN_DNS_LAST_RTT,
            -1L
        )
        addStatus(
            "VPN DNS 健康",
            "$vpnDnsHealth${
                if (vpnDnsRtt >= 0L) " · ${vpnDnsRtt}ms" else ""
            } · DnsResolver NO_CACHE 绑定当前 VPN",
            serviceAlive && vpnDnsHealth == "HEALTHY"
        )
        val httpsText = when {
            !serviceAlive && enabled && !paused ->
                "服务心跳缺失 · 旧 HTTP 证据不作为健康依据"
            (lastSuccess > 0L && !successGenerationMatches) ||
                (lastAttemptRtt >= 0L && !attemptGenerationMatches) ||
                (lastSuccess > 0L && !successNetworkMatches) ||
                (lastSuccess > 0L && !successSessionMatches) ||
                (
                    lastSuccess <= 0L &&
                        lastAttemptRtt >= 0L &&
                        (!attemptNetworkMatches || !attemptSessionMatches)
                    ) ->
                "等待本代 VPN session 完成首次 VPN-only 探测"
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
        addStatus("VPN HTTPS 204", httpsText, httpsHealthy)
        addStatus(
            "GMS_PACKAGE_AVAILABLE",
            gms.explanation,
            gms.available
        )
        val mtalkCurrent =
            status.string(
                LuonnotarPreferences.KEY_MTALK_LAST_SESSION_FINGERPRINT
            ).isNotBlank() &&
                status.string(
                    LuonnotarPreferences.KEY_MTALK_LAST_SESSION_FINGERPRINT
                ) ==
                status.string(
                    LuonnotarPreferences.KEY_VPN_SESSION_FINGERPRINT
                )
        fun portMatrix(family: String): String {
            val prefix =
                if (family == "IPv4") "mtalk_ipv4_tcp_"
                else "mtalk_ipv6_tcp_"
            return listOf(5228, 443).joinToString(" / ") {
                "$it ${yesNo(status.boolean(prefix + it))}"
            }
        }
        addStatus(
            "FCM mtalk 路径诊断",
            if (!mtalkCurrent) {
                "等待当前 VPN session 的 mtalk DNS/TCP 探测"
            } else {
                "IPv4 DNS ${
                    yesNo(
                        status.boolean(
                            LuonnotarPreferences.KEY_MTALK_IPV4_DNS
                        )
                    )
                } · ${portMatrix("IPv4")}\nIPv6 DNS ${
                    yesNo(
                        status.boolean(
                            LuonnotarPreferences.KEY_MTALK_IPV6_DNS
                        )
                    )
                } · ${portMatrix("IPv6")} · GMS MCS socket 仍为 UNKNOWN"
            },
            false
        )
        val pushTestSequence = status.long(
            LuonnotarPreferences.KEY_PUSH_TEST_LAST_SEQUENCE
        )
        val pushTestSenderEpoch = status.long(
            LuonnotarPreferences.KEY_PUSH_TEST_LAST_SENDER_EPOCH_MS
        )
        val pushTestSeenWall = status.long(
            LuonnotarPreferences.KEY_PUSH_TEST_LAST_SEEN_WALL
        )
        val pushTestDelay = status.long(
            LuonnotarPreferences.KEY_PUSH_TEST_LAST_DELAY_MS,
            -1L
        )
        val pushTestPackage = status.string(
            LuonnotarPreferences.KEY_PUSH_TEST_LAST_PACKAGE
        )
        val pushEvidence = PushTestDeliveryPolicy.evaluate(
            nowWall = System.currentTimeMillis(),
            sequence = pushTestSequence,
            senderEpochMs = pushTestSenderEpoch,
            seenWall = pushTestSeenWall
        )
        val controlledDeliveryRecent =
            pushEvidence.state == ControlledPushDeliveryState.RECENT
        addStatus(
            "真实 FCM Canary",
            when (pushEvidence.state) {
                ControlledPushDeliveryState.UNVERIFIED ->
                    "NOT_OBSERVED · 等待严格 PUSH_TEST_<n> 时间戳消息"
                ControlledPushDeliveryState.CLOCK_INVALID ->
                    "CLOCK_INVALID · 发送端与接收端时钟无法形成可信延迟"
                ControlledPushDeliveryState.STALE ->
                    "STALE · 最近 PUSH_TEST_$pushTestSequence 已过期"
                ControlledPushDeliveryState.RECENT ->
                    "OBSERVED · PUSH_TEST_$pushTestSequence · $pushTestPackage"
            },
            controlledDeliveryRecent
        )
        addStatus(
            "FCM 传输可达",
            if (controlledDeliveryRecent) {
                "CONTROLLED_DELIVERY_OBSERVED · 仅证明最近一次端到端送达，不证明 MCS 持久在线"
            } else {
                "FCM_TRANSPORT_UNVERIFIED · HTTPS 204 与 5228 TCP 均不能证明 MCS 会话"
            },
            controlledDeliveryRecent
        )
        addStatus(
            "FCM 真实送达",
            if (pushTestSequence > 0L && pushTestDelay >= 0L) {
                "PUSH_TEST_$pushTestSequence · 端到端约 ${pushTestDelay}ms · 接收 ${formatWallClock(pushTestSeenWall)}"
            } else {
                "FCM_DELIVERY_UNVERIFIED · 需受控发送时间与通知监听时间配对"
            },
            controlledDeliveryRecent
        )
        val notificationAccess =
            NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val notificationAcknowledged =
            status.boolean(LuonnotarPreferences.KEY_NOTIFICATION_PRIVACY_ACK)
        val notificationConnected =
            status.boolean(LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_CONNECTED) &&
                status.integer(LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_PID) > 0 &&
                status.long(
                    LuonnotarPreferences
                        .KEY_NOTIFICATION_LISTENER_HEARTBEAT_ELAPSED
                ).let {
                    it > 0L &&
                        it <= SystemClock.elapsedRealtime() &&
                        SystemClock.elapsedRealtime() - it <= 10 * 60_000L
                }
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
            statusDetails.remove("自动恢复异常")
            statusRows["自动恢复异常"]?.row?.visibility = View.GONE
        }
        serviceButton.text = when {
            !enabled -> "开启极限保活"
            paused -> "继续极限保活"
            else -> "停止极限保活"
        }
        serviceButton.contentDescription = "${serviceButton.text}，核心操作"
        aggressiveModeButton.text =
            when (runtimeProfile) {
                GuardianRuntimeProfile.LAB_EXTREME ->
                    "调整实验室策略 · L$labLevel"
                GuardianRuntimeProfile.ADB_PASSIVE ->
                    "调整 ADB 隐身策略"
                GuardianRuntimeProfile.IQOO_COOPERATIVE ->
                    "调整 iQOO 自适应策略"
                GuardianRuntimeProfile.STANDARD ->
                    "调整标准守护策略"
            }
        aggressiveModeButton.contentDescription =
            "${aggressiveModeButton.text}，逐项配置 CPU、Wi‑Fi、DNS、HTTPS、诊断和监控目标"
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
        refreshDetailedStatusButton()
        refreshVisualSummary()
    }

    private fun refreshDetailedStatusButton() {
        if (!::detailedStatusButton.isInitialized) return
        val hiddenCount = statusDetails.keys.count { it !in CORE_STATUS_LABELS }
        detailedStatusButton.text = "查看详细诊断状态 · $hiddenCount 项"
        detailedStatusButton.contentDescription = "查看 $hiddenCount 项详细诊断状态"
    }

    private fun showDetailedStatusDialog(anchorView: View? = null) {
        val details = statusDetails
            .filterKeys { it !in CORE_STATUS_LABELS }
            .entries
            .joinToString("\n\n") { (label, detail) ->
                val state = if (detail.good) "正常" else "需关注 / 尚未验证"
                "$label\n$state · ${detail.value}"
            }
            .ifBlank { "暂无详细诊断数据" }
        GlassMessageDialog(
            context = this,
            preferences = VisualPreferences.load(this),
            visualBackground = visualBackground,
            title = "详细诊断状态",
            message = details,
            anchorView = anchorView
        ).show()
    }

    private fun addStatus(label: String, value: String, good: Boolean) {
        statusDetails[label] = StatusDetail(value, good)
        if (label !in CORE_STATUS_LABELS) return
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
            if (guardianProfile(status) != GuardianRuntimeProfile.ADB_PASSIVE) {
                FcmRecoveryWorker.ensurePeriodic(this)
            }
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
            if (guardianProfile(status) != GuardianRuntimeProfile.ADB_PASSIVE) {
                FcmRecoveryWorker.enqueue(this, "user_enabled")
                FcmRecoveryWorker.ensurePeriodic(this)
            } else {
                FcmRecoveryWorker.cancelPeriodic(this)
            }
        }
    }

    private fun guardianProfile(status: Bundle): GuardianRuntimeProfile =
        runCatching {
            GuardianRuntimeProfile.valueOf(
                status.string(
                    LuonnotarPreferences.KEY_GUARDIAN_PROFILE,
                    GuardianRuntimeProfile.STANDARD.name
                )
            )
        }.getOrDefault(GuardianRuntimeProfile.STANDARD)

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
            nowUptime = SystemClock.uptimeMillis(),
            lastTickUptime = status.long(LuonnotarPreferences.KEY_LAST_TICK_UPTIME),
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

    private fun showGuardianExperimentDialog(anchor: View) {
        val status = readGuardianStatus()
        if (!status.containsKey(LuonnotarPreferences.KEY_KEEPER_PROCESS_PID)) {
            showGlassNotice(
                "Keeper 状态不可读，暂时无法修改守护策略",
                long = true
            )
            return
        }
        val profile = runCatching {
            GuardianRuntimeProfile.valueOf(
                status.string(
                    LuonnotarPreferences.KEY_GUARDIAN_PROFILE,
                    GuardianProfilePolicy.defaultProfile(
                        isVivoFamily()
                    ).name
                )
            )
        }.getOrDefault(
            GuardianProfilePolicy.defaultProfile(isVivoFamily())
        )
        val values = GuardianProfilePolicy.experimentKeys.associateWith {
            status.boolean(it)
        }
        GuardianExperimentDialog(
            context = this,
            preferences = VisualPreferences.load(this),
            visualBackground = visualBackground,
            anchorView = anchor,
            initialProfile = profile,
            initialLevel = status.integer(
                LuonnotarPreferences.KEY_LAB_EXTREME_LEVEL,
                0
            ),
            initialValues = values
        ) { selectedProfile, level, experiments ->
            if (
                !GuardianStatusClient.setRuntimeConfig(
                    this,
                    selectedProfile,
                    level,
                    experiments
                )
            ) {
                showGlassNotice(
                    "守护策略保存失败；原设置保持不变",
                    long = true
                )
                return@GuardianExperimentDialog
            }
            LogManager.event(
                this,
                "guardian_runtime_config_changed",
                mapOf(
                    "profile" to selectedProfile.name,
                    "labLevel" to level,
                    "enabledExperiments" to experiments
                        .filterValues { it }
                        .keys
                        .sorted()
                        .joinToString(",")
                )
            )
            if (
                status.boolean(LuonnotarPreferences.KEY_ENABLED) &&
                !status.boolean(LuonnotarPreferences.KEY_PAUSED)
            ) {
                runCatching {
                    startService(
                        Intent(this, FcmGuardianService::class.java)
                            .setAction(
                                FcmGuardianService.ACTION_PROFILE_CHANGED
                            )
                    )
                }
            }
            showGlassNotice(
                when (selectedProfile) {
                    GuardianRuntimeProfile.IQOO_COOPERATIVE ->
                        "已应用 iQOO 自适应可靠性模式：默认启用息屏 CPU Guard，保留 120 秒网络静默；Wi-Fi Lock 与周期探测保持独立"
                    GuardianRuntimeProfile.ADB_PASSIVE ->
                        "已应用 ADB 一次性隐身验证：运行 60 秒后自动关闭守护"
                    GuardianRuntimeProfile.STANDARD ->
                        "已应用标准模式；熄屏 CPU Guard 默认开启，仍可在主界面关闭"
                    GuardianRuntimeProfile.LAB_EXTREME ->
                        "已应用实验室 L$level；请观察 GMS 冻结次数、耗电和真实推送后再升级"
                },
                long = true
            )
            renderStatus()
        }.show()
    }

    private fun isVivoFamily(): Boolean {
        val vendor = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return "vivo" in vendor || "iqoo" in vendor
    }

    private fun showOemGuide(anchorView: View? = null) {
        val vendor = "${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL}"
        val steps = when {
            vendor.contains("vivo", true) || vendor.contains("iqoo", true) ->
                "检测到 vivo/iQOO：\n• 默认使用自适应可靠性模式：守护开启且熄屏时持续持有 CPU WakeLock，亮屏立即释放；保留 120 秒网络静默，不长期占用 Wi‑Fi 高性能锁\n• 努昂诺塔和当前 VPN（Proton/Tailscale）：允许自启动、后台运行、电池不限制、最近任务锁定、常驻通知\n• i 管家不得自动清理\n• WhatsApp/GMS：允许后台数据与后台运行，GMS 保持 Doze 白名单\n• 实验室 L0–L4 仅用于逐项 A/B；强度升高后若冻结次数或推送延迟增加，应立即降级\n• 原生 Doze 关闭不代表 vivo PEM、QuickFrozen 或后台清理已关闭"
            vendor.contains("xiaomi", true) || vendor.contains("redmi", true) ->
                "检测到小米/红米：\n• 可在主界面手动开启“熄屏 CPU Guard”做单变量 A/B\n• 努昂诺塔、当前 VPN（Proton/Tailscale）、WhatsApp、GMS 分别开启自启动\n• 电池策略设为“无限制”，允许后台数据，最近任务锁定\n• HyperOS 更新后重新检查\n• millet_white 仅作高级实验，不由本应用修改"
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
              "com.tailscale.ipn",
              "com.termux",
              "com.termux.boot"
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
        ThemeTransitionSnapshot.capture(rootContainer, scrollView.scrollY)
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

    private fun isIgnoringBatteryOptimizations(): Boolean =
        runCatching {
            getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(false)

    private fun updateBatteryOptimizationButton() {
        if (!::batteryOptimizationButton.isInitialized) return
        val exempt = isIgnoringBatteryOptimizations()
        batteryOptimizationButton.text = if (exempt) {
            "努昂诺塔已不受电池优化"
        } else {
            "请求努昂诺塔不受电池优化"
        }
        batteryOptimizationButton.contentDescription = batteryOptimizationButton.text
    }

    private fun requestOwnBatteryOptimizationExemption() {
        if (isIgnoringBatteryOptimizations()) {
            showGlassNotice("努昂诺塔已经不受原生电池优化限制")
            updateBatteryOptimizationButton()
            return
        }
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }.recoverCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.recoverCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            showGlassNotice("无法打开电池优化设置：${it.javaClass.simpleName}", long = true)
        }
    }

    private fun toggleGuardianExperiment(
        key: String,
        label: String
    ) {
        val status = readGuardianStatus()
        if (
            !status.containsKey(
                LuonnotarPreferences.KEY_KEEPER_PROCESS_PID
            )
        ) {
            showGlassNotice(
                "Keeper 状态不可读，无法修改$label",
                long = true
            )
            return
        }
        if (
            guardianProfile(status) ==
            GuardianRuntimeProfile.ADB_PASSIVE
        ) {
            showGlassNotice(
                "ADB 一次性验证固定关闭$label"
            )
            return
        }

        val newValue = !status.boolean(key)
        if (
            !GuardianStatusClient.setExperiment(
                this,
                key,
                newValue
            )
        ) {
            showGlassNotice(
                "$label 写入失败",
                long = true
            )
            return
        }

        LogManager.event(
            this,
            "guardian_feature_config_changed",
            mapOf(
                "key" to key,
                "enabled" to newValue
            )
        )

        if (
            status.boolean(LuonnotarPreferences.KEY_ENABLED) &&
            !status.boolean(LuonnotarPreferences.KEY_PAUSED)
        ) {
            runCatching {
                startService(
                    Intent(this, FcmGuardianService::class.java)
                        .setAction(
                            FcmGuardianService.ACTION_PROFILE_CHANGED
                        )
                )
            }.onFailure {
                showGlassNotice(
                    "配置已保存，但守护即时重载失败：${it.javaClass.simpleName}",
                    long = true
                )
            }
        }

        showGlassNotice(
            "$label 已${if (newValue) "开启" else "关闭"}"
        )
        renderStatus()
    }

    private fun updateGuardianFeatureButtons(status: Bundle) {
        if (
            !::wifiLockButton.isInitialized ||
            !::periodicDnsButton.isInitialized ||
            !::periodicHttpsButton.isInitialized
        ) return

        val statusAvailable = status.containsKey(
            LuonnotarPreferences.KEY_KEEPER_PROCESS_PID
        )
        val passive =
            guardianProfile(status) == GuardianRuntimeProfile.ADB_PASSIVE
        val editable = statusAvailable && !passive

        val wifiEnabled = status.boolean(
            LuonnotarPreferences.KEY_EXPERIMENT_HIGH_PERF_WIFI_LOCK
        )
        val dnsEnabled = status.boolean(
            LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_DNS
        )
        val httpsEnabled = status.boolean(
            LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_HTTPS
        )

        wifiLockButton.isEnabled = editable
        periodicDnsButton.isEnabled = editable
        periodicHttpsButton.isEnabled = editable

        wifiLockButton.text =
            if (wifiEnabled) {
                "关闭高性能 Wi-Fi Lock"
            } else {
                "开启高性能 Wi-Fi Lock"
            }
        periodicDnsButton.text =
            if (dnsEnabled) {
                "关闭周期 VPN DNS 检测"
            } else {
                "开启周期 VPN DNS 检测"
            }
        periodicHttpsButton.text =
            if (httpsEnabled) {
                "关闭周期 HTTPS 204 检测"
            } else {
                "开启周期 HTTPS 204 检测"
            }
    }

    private fun startEmbeddedGuardianSetup() {
        val runtimePermissionGranted = embeddedRuntimeNotificationPermissionGranted()
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val channelEnabled = embeddedRebootAlertChannelEnabled()
        val settingsVisited = EmbeddedRebootAlertGuideStore.settingsVisited(this)
        if (
            EmbeddedRebootAlertGuidePolicy.shouldRunGuide(
                sdkInt = Build.VERSION.SDK_INT,
                runtimePermissionGranted = runtimePermissionGranted,
                notificationsEnabled = notificationsEnabled,
                channelEnabled = channelEnabled,
                settingsVisited = settingsVisited
            )
        ) {
            beginEmbeddedRebootAlertGuide(continueEmbeddedSetup = true)
            return
        }
        startEmbeddedGuardianSetupNow()
    }

    private fun startEmbeddedGuardianSetupNow() {
        EmbeddedGuardianManager.startSetup(this, "main_activity")
        showGlassNotice("已启动本机无线 ADB 向导；开启无线调试后看通知栏", long = true)
        mainHandler.postDelayed({ updatePrivilegedGuardianUi() }, 800L)
    }

    private fun beginEmbeddedRebootAlertGuide(continueEmbeddedSetup: Boolean) {
        NotificationChannelManager.create(this)
        if (
            EmbeddedRebootAlertGuidePolicy.requiresRuntimePermission(
                sdkInt = Build.VERSION.SDK_INT,
                runtimePermissionGranted = embeddedRuntimeNotificationPermissionGranted()
            )
        ) {
            EmbeddedRebootAlertGuideStore.beginPermissionFlow(
                this,
                continueEmbeddedSetup = continueEmbeddedSetup
            )
            LogManager.event(
                this,
                "embedded_reboot_alert_permission_requested",
                mapOf("continueEmbeddedSetup" to continueEmbeddedSetup)
            )
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        openEmbeddedRebootAlertSettings(continueEmbeddedSetup)
    }

    private fun handleEmbeddedRebootNotificationPermissionResult(granted: Boolean) {
        if (!EmbeddedRebootAlertGuideStore.permissionFlowPending(this)) return
        val continueSetup = EmbeddedRebootAlertGuideStore.pendingContinuation(this)
        EmbeddedRebootAlertGuideStore.clearPermissionFlow(this)
        LogManager.event(
            this,
            "embedded_reboot_alert_permission_result",
            mapOf(
                "granted" to granted,
                "continueEmbeddedSetup" to continueSetup
            )
        )
        if (!granted) {
            showGlassNotice(
                "需要先允许通知，才能显示配对进度和重启横幅提醒",
                long = true
            )
            updateEmbeddedRebootAlertUi()
            return
        }
        openEmbeddedRebootAlertSettings(continueSetup)
    }

    private fun openEmbeddedRebootAlertSettings(continueEmbeddedSetup: Boolean) {
        NotificationChannelManager.create(this)
        EmbeddedRebootAlertGuideStore.markSettingsOpened(
            this,
            continueEmbeddedSetup = continueEmbeddedSetup
        )
        LogManager.event(
            this,
            "embedded_reboot_alert_settings_opened",
            mapOf(
                "continueEmbeddedSetup" to continueEmbeddedSetup,
                "channelId" to NotificationChannelManager.PRIVILEGED_REBOOT_CHANNEL_ID
            )
        )
        val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(
                Settings.EXTRA_CHANNEL_ID,
                NotificationChannelManager.PRIVILEGED_REBOOT_CHANNEL_ID
            )
        runCatching { startActivity(channelIntent) }
            .recoverCatching {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
            .onFailure { error ->
                EmbeddedRebootAlertGuideStore.clearSettingsReturn(this)
                LogManager.event(
                    this,
                    "embedded_reboot_alert_settings_failed",
                    mapOf("error" to error.toString())
                )
                showGlassNotice(
                    "无法打开重启横幅设置：${error.javaClass.simpleName}",
                    long = true
                )
                if (continueEmbeddedSetup) startEmbeddedGuardianSetupNow()
            }
    }

    private fun consumeEmbeddedRebootAlertSettingsReturn() {
        val plan = EmbeddedRebootAlertGuideStore.consumeSettingsReturn(this) ?: return
        val posted = sendEmbeddedRebootAlertTest(
            source = "settings_return",
            openGuideWhenUnavailable = false
        )
        if (plan.continueEmbeddedSetup && posted) {
            mainHandler.postDelayed({ startEmbeddedGuardianSetupNow() }, 500L)
        }
    }

    private fun sendEmbeddedRebootAlertTest(
        source: String,
        openGuideWhenUnavailable: Boolean = true
    ): Boolean {
        if (!embeddedNotificationsAllowed() || !embeddedRebootAlertChannelEnabled()) {
            if (openGuideWhenUnavailable) {
                beginEmbeddedRebootAlertGuide(continueEmbeddedSetup = false)
            } else {
                showGlassNotice(
                    "重启提醒通道仍不可用，请打开通知和悬浮通知后再测试",
                    long = true
                )
                updateEmbeddedRebootAlertUi()
            }
            return false
        }
        val posted = EmbeddedGuardianNotifier.showRebootAlertTest(this, source)
        if (posted) {
            EmbeddedRebootAlertGuideStore.markTestSent(this)
            showGlassNotice(
                "已发送测试横幅；若仍只出现在通知中心，请在刚才的页面打开“悬浮通知”",
                long = true
            )
        } else {
            showGlassNotice("测试横幅未能发布，请检查通知权限", long = true)
        }
        updateEmbeddedRebootAlertUi()
        return posted
    }

    private fun embeddedRuntimeNotificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun embeddedNotificationsAllowed(): Boolean =
        embeddedRuntimeNotificationPermissionGranted() &&
            NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun embeddedRebootAlertChannelEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        val channel = getSystemService(NotificationManager::class.java)
            .getNotificationChannel(NotificationChannelManager.PRIVILEGED_REBOOT_CHANNEL_ID)
        return channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun updateEmbeddedRebootAlertUi() {
        if (
            !::embeddedRebootAlertSummary.isInitialized ||
            !::embeddedRebootAlertSetupButton.isInitialized ||
            !::embeddedRebootAlertTestButton.isInitialized
        ) return
        val permissionGranted = embeddedNotificationsAllowed()
        val channelEnabled = embeddedRebootAlertChannelEnabled()
        val settingsVisited = EmbeddedRebootAlertGuideStore.settingsVisited(this)
        val testSent = EmbeddedRebootAlertGuideStore.lastTestSentElapsed(this) > 0L

        embeddedRebootAlertSummary.text = when {
            !permissionGranted ->
                "通知授权：未允许 · 重启横幅：不可用"
            !channelEnabled ->
                "通知授权：已允许 · 重启提醒通道：已关闭"
            !settingsVisited ->
                "通知授权：已允许 · 请进入系统设置打开“悬浮通知”；返回后自动测试"
            testSent ->
                "通知授权：已允许 · 通道已启用 · 已发送过测试横幅；悬浮状态以实测为准"
            else ->
                "通知授权：已允许 · 通道已启用 · 悬浮开关无法自动读取，请发送测试横幅确认"
        }
        embeddedRebootAlertSummary.setTextColor(
            when {
                !permissionGranted || !channelEnabled -> palette().warning
                else -> palette().secondary
            }
        )
        embeddedRebootAlertSetupButton.text = when {
            !permissionGranted -> "授权通知并配置重启横幅"
            else -> "打开重启横幅设置"
        }
        embeddedRebootAlertTestButton.isEnabled = permissionGranted && channelEnabled
    }

    private fun stopEmbeddedGuardian() {
        EmbeddedGuardianManager.stop(this, "main_activity") { result ->
            runOnUiThread {
                result.onSuccess { showGlassNotice("内置特权引擎已停止") }
                    .onFailure { showGlassNotice(it.message ?: it.javaClass.simpleName, long = true) }
                updatePrivilegedGuardianUi()
            }
        }
    }

    private fun runEmbeddedGuardianCycle() {
        EmbeddedGuardianManager.runCycle(this) { result ->
            runOnUiThread {
                result.onSuccess { showGlassNotice("内置特权守护周期已完成") }
                    .onFailure { showGlassNotice(it.message ?: it.javaClass.simpleName, long = true) }
                updatePrivilegedGuardianUi()
            }
        }
    }

    private fun runEmbeddedGuardianGmsRecovery() {
        showGlassNotice("正在向 shell 引擎提交 GMS 深度恢复；引擎保持在线并在后台验证", long = true)
        EmbeddedGuardianManager.recoverGms(this) { result ->
            runOnUiThread {
                result.onSuccess {
                    val response = runCatching { JSONObject(it) }.getOrNull()
                    val requestId = response?.optLong("requestId", -1L) ?: -1L
                    val coalesced = response?.optBoolean("coalesced", false) == true
                    showGlassNotice(
                        when {
                            coalesced -> "已有 GMS 深度恢复正在排队或执行；未重复启动"
                            requestId >= 0L -> "GMS 深度恢复已提交（请求 $requestId）；结果将写入诊断状态"
                            else -> "GMS 深度恢复已提交；结果将在后台验证"
                        },
                        long = true
                    )
                }
                    .onFailure { showGlassNotice(it.message ?: it.javaClass.simpleName, long = true) }
                updatePrivilegedGuardianUi()
            }
        }
    }

    private fun runEmbeddedBackgroundPolicyRepair() {
        showGlassNotice(
            "正在识别厂商并修复努昂诺塔、WhatsApp、GMS 与 VPN 的 ADB 后台白名单",
            long = true
        )
        EmbeddedGuardianManager.applyBackgroundPolicy(this, "main_activity") { result ->
            runOnUiThread {
                result.onSuccess {
                    val report = EmbeddedBackgroundPolicyStore.snapshot(this).report
                    showGlassNotice(
                        if (report.createdElapsed > 0L) {
                            "后台策略完成：${report.verifiedTargets}/${report.installedTargets} 个已安装目标通过核心验证"
                        } else {
                            "后台策略命令已完成"
                        },
                        long = true
                    )
                }.onFailure {
                    showGlassNotice(it.message ?: it.javaClass.simpleName, long = true)
                }
                updateEmbeddedGuardianUiOnly()
            }
        }
    }

    private fun openOemBackgroundSettings() {
        val policy = EmbeddedBackgroundPolicyStore.snapshot(this)
        val family = if (policy.hasReport) {
            policy.report.device.family
        } else {
            BackgroundPolicyVendorFamily.UNKNOWN
        }
        OemBackgroundSettingsNavigator.open(this, family)
            .onSuccess { destination ->
                LogManager.event(
                    this,
                    "embedded_oem_background_settings_opened",
                    mapOf(
                        "vendor" to family.name,
                        "destination" to destination
                    )
                )
            }
            .onFailure { error ->
                LogManager.event(
                    this,
                    "embedded_oem_background_settings_failed",
                    mapOf(
                        "vendor" to family.name,
                        "error" to error.toString()
                    )
                )
                showGlassNotice(
                    "无法打开厂商后台设置：${error.javaClass.simpleName}",
                    long = true
                )
            }
    }

    private fun updateEmbeddedBackgroundPolicyUi() {
        if (
            !::embeddedBackgroundPolicySummary.isInitialized ||
            !::embeddedBackgroundPolicyApplyButton.isInitialized ||
            !::embeddedBackgroundPolicySettingsButton.isInitialized
        ) return
        val engine = EmbeddedGuardianStore.snapshot(this)
        val policy = EmbeddedBackgroundPolicyStore.snapshot(this)
        embeddedBackgroundPolicySummary.text = when {
            policy.hasReport -> buildString {
                append(policy.report.conciseSummary())
                if (policy.report.oemGuidance.isNotBlank()) {
                    append('\n')
                    append(policy.report.oemGuidance)
                }
            }
            engine.liveConnected ->
                "内置引擎已连接；首轮守护会自动识别厂商并应用标准 ADB 后台白名单。"
            engine.featureEnabled ->
                "引擎尚未运行；启动后自动处理努昂诺塔、WhatsApp、GMS 和已安装 VPN。"
            else ->
                "启用内置特权引擎后，可自动识别厂商并修复标准 ADB 后台策略。"
        }
        embeddedBackgroundPolicySummary.setTextColor(
            when {
                policy.hasReport &&
                    policy.report.installedTargets > 0 &&
                    policy.report.verifiedTargets == policy.report.installedTargets &&
                    !policy.report.requiresOemUserAction -> palette().good
                policy.hasReport -> palette().warning
                else -> palette().secondary
            }
        )
        embeddedBackgroundPolicyApplyButton.isEnabled = engine.liveConnected
        embeddedBackgroundPolicySettingsButton.isEnabled = policy.hasReport
        embeddedBackgroundPolicySettingsButton.text = when {
            policy.hasReport && policy.report.requiresOemUserAction -> "打开厂商私有后台设置"
            else -> "打开系统后台设置"
        }
    }

    private fun requestPrivilegedGuardianPermission() {
        PrivilegedGuardianController.requestPermission(this)
        showGlassNotice("已请求连接 Shizuku / Sui；首次使用请授权努昂诺塔")
        mainHandler.postDelayed({ updatePrivilegedGuardianUi() }, 600L)
    }

    private fun togglePrivilegedGuardian() {
        val snapshot = PrivilegedGuardianController.snapshot(this)
        val enable = !snapshot.configuredEnabled
        PrivilegedGuardianController.setEnabled(this, enable) { result ->
            runOnUiThread {
                result.onSuccess {
                    showGlassNotice(
                        if (enable) {
                            "Privileged Guardian 已请求启动；首轮会立即校准并解冻目标进程"
                        } else {
                            "Privileged Guardian 已停止"
                        }
                    )
                }.onFailure { error ->
                    showGlassNotice(error.message ?: error.javaClass.simpleName, long = true)
                }
                updatePrivilegedGuardianUi()
            }
        }
    }

    private fun runPrivilegedGuardianCycle() {
        PrivilegedGuardianController.runCycle(this) { result ->
            runOnUiThread {
                result.onSuccess {
                    showGlassNotice("特权守护周期已完成")
                }.onFailure { error ->
                    showGlassNotice(error.message ?: error.javaClass.simpleName, long = true)
                }
                updatePrivilegedGuardianUi()
            }
        }
    }

    private fun togglePrivilegedGmsRecovery() {
        val snapshot = PrivilegedGuardianController.snapshot(this)
        val enable = !snapshot.gmsRecoveryEnabled
        PrivilegedGuardianController.setGmsRecoveryEnabled(this, enable) { shizukuResult ->
            val persisted = PrivilegedGuardianController.snapshot(this).gmsRecoveryEnabled == enable
            if (!persisted) {
                runOnUiThread {
                    val error = shizukuResult.exceptionOrNull()
                    showGlassNotice(
                        error?.message ?: "自动 GMS 深度恢复设置写入失败",
                        long = true
                    )
                    updatePrivilegedGuardianUi()
                }
                return@setGmsRecoveryEnabled
            }

            val embedded = EmbeddedGuardianStore.snapshot(this)
            if (embedded.featureEnabled && embedded.liveConnected) {
                EmbeddedGuardianManager.reconfigure(this) { embeddedResult ->
                    runOnUiThread {
                        embeddedResult.onSuccess {
                            val shizukuWarning = shizukuResult.exceptionOrNull()?.let { error ->
                                "；Shizuku 后备同步失败：${error.message ?: error.javaClass.simpleName}"
                            }.orEmpty()
                            showGlassNotice(
                                if (enable) {
                                    "自动 GMS 深度恢复已开启，并已同步到内置 Shell 引擎$shizukuWarning"
                                } else {
                                    "自动 GMS 深度恢复已关闭，并已同步到内置 Shell 引擎$shizukuWarning"
                                },
                                long = true
                            )
                        }.onFailure { error ->
                            showGlassNotice(
                                "设置已保存，但内置 Shell 引擎同步失败：" +
                                    (error.message ?: error.javaClass.simpleName),
                                long = true
                            )
                        }
                        updatePrivilegedGuardianUi()
                    }
                }
            } else {
                runOnUiThread {
                    val remoteError = shizukuResult.exceptionOrNull()
                    showGlassNotice(
                        when {
                            remoteError != null ->
                                "设置已保存；当前没有可同步的特权引擎：" +
                                    (remoteError.message ?: remoteError.javaClass.simpleName)
                            enable ->
                                "自动 GMS 深度恢复已开启；下次启动 Shell 引擎时生效"
                            else ->
                                "自动 GMS 深度恢复已关闭；手动恢复仍可使用"
                        },
                        long = true
                    )
                    updatePrivilegedGuardianUi()
                }
            }
        }
    }

    private fun runPrivilegedGmsRecoveryNow() {
        showGlassNotice("正在 force-stop GMS，并等待新 PID；最多约 45 秒", long = true)
        PrivilegedGuardianController.recoverGmsNow(this) { result ->
            runOnUiThread {
                result.onSuccess {
                    val snapshot = PrivilegedGuardianController.snapshot(this)
                    showGlassNotice(
                        "GMS 深度恢复结果：${snapshot.lastGmsRecoveryResult}",
                        long = true
                    )
                }.onFailure { error ->
                    showGlassNotice(error.message ?: error.javaClass.simpleName, long = true)
                }
                updatePrivilegedGuardianUi()
            }
        }
    }

    private fun updatePrivilegedGuardianUi() {
        if (
            !::embeddedGuardianSummary.isInitialized ||
            !::embeddedGuardianSetupButton.isInitialized ||
            !::embeddedGuardianStopButton.isInitialized ||
            !::embeddedGuardianCycleButton.isInitialized ||
            !::embeddedGuardianGmsButton.isInitialized ||
            !::embeddedRebootAlertSummary.isInitialized ||
            !::embeddedRebootAlertSetupButton.isInitialized ||
            !::embeddedRebootAlertTestButton.isInitialized ||
            !::privilegedGuardianSummary.isInitialized ||
            !::privilegedGuardianPermissionButton.isInitialized ||
            !::privilegedGuardianToggleButton.isInitialized ||
            !::privilegedGuardianCycleButton.isInitialized ||
            !::privilegedGmsRecoverySummary.isInitialized ||
            !::privilegedGmsRecoveryToggleButton.isInitialized ||
            !::privilegedGmsRecoveryNowButton.isInitialized
        ) return
        EmbeddedGuardianManager.refreshIfStale(this) { runOnUiThread { updateEmbeddedGuardianUiOnly() } }
        updateEmbeddedGuardianUiOnly()
        PrivilegedGuardianController.refreshIfStale(this)
        val snapshot = PrivilegedGuardianController.snapshot(this)
        privilegedGuardianPermissionButton.text = when {
            !snapshot.shizukuAvailable -> "重新检测 Shizuku / Sui"
            !snapshot.permissionGranted -> "授予 Shizuku 权限"
            snapshot.connectionState == "connected" -> "Shizuku / Sui 已连接"
            else -> "连接 Shizuku / Sui"
        }
        privilegedGuardianPermissionButton.isEnabled =
            !snapshot.permissionGranted || snapshot.connectionState != "connected"
        privilegedGuardianToggleButton.text =
            if (snapshot.configuredEnabled) {
                "停止 Privileged Guardian"
            } else {
                "开启 Privileged Guardian"
            }
        privilegedGuardianCycleButton.isEnabled =
            snapshot.configuredEnabled && snapshot.shizukuAvailable && snapshot.permissionGranted
        privilegedGmsRecoveryToggleButton.text =
            if (snapshot.gmsRecoveryEnabled) {
                "关闭自动 GMS 深度恢复"
            } else {
                "开启自动 GMS 深度恢复"
            }
        privilegedGmsRecoveryToggleButton.isEnabled = snapshot.configuredEnabled && snapshot.running
        privilegedGmsRecoveryNowButton.isEnabled =
            snapshot.configuredEnabled && snapshot.running && !snapshot.gmsRecoveryInProgress
        privilegedGmsRecoverySummary.text = buildString {
            append(if (snapshot.gmsRecoveryEnabled) "自动恢复：开启" else "自动恢复：关闭")
            append(" · 10 分钟冻结证据 ${snapshot.gmsFreezeEventsInWindow}/3")
            append(" · PID重建 ${snapshot.gmsPidRestartCount}/${snapshot.gmsRecoveryAttemptCount}")
            append(" · MCS验证 ${snapshot.gmsTransportVerifiedRecoveryCount}")
            append(
                " · MCS=" + when {
                    !snapshot.gmsTransportObservable -> "不可观测"
                    snapshot.gmsTransportHealthy -> "在线:${snapshot.gmsTransportPorts.joinToString()}"
                    else -> "缺失×${snapshot.gmsTransportConsecutiveMissing}"
                }
            )
            if (snapshot.gmsBadAuthenticationCount > 0) {
                append(" · 认证错误 ${snapshot.gmsBadAuthenticationCount}")
            }
            append(" · 上次 ${snapshot.lastGmsRecoveryResult}")
            snapshot.lastGmsRecoveryTrigger.takeIf(String::isNotBlank)?.let { append(" ($it)") }
            if (snapshot.gmsRecoveryInProgress) append(" · 正在重启并验证 MCS")
        }
        privilegedGmsRecoverySummary.setTextColor(
            when {
                snapshot.gmsRecoveryInProgress -> palette().warning
                snapshot.lastGmsRecoveryResult == "restarted_transport_verified" -> palette().good
                else -> palette().secondary
            }
        )

        privilegedGuardianSummary.text = formatPrivilegedGuardianSummary(snapshot)
        privilegedGuardianSummary.setTextColor(
            when {
                snapshot.running && snapshot.privileged -> palette().good
                snapshot.configuredEnabled -> palette().warning
                else -> palette().secondary
            }
        )
    }

    private fun updateEmbeddedGuardianUiOnly() {
        if (!::embeddedGuardianSummary.isInitialized) return
        updateEmbeddedRebootAlertUi()
        updateEmbeddedBackgroundPolicyUi()
        val snapshot = EmbeddedGuardianStore.snapshot(this)
        val presentation = EmbeddedGuardianStatePolicy.presentation(snapshot.runtime)
        embeddedGuardianSetupButton.text = presentation.setupButtonText
        embeddedGuardianSetupButton.isEnabled = presentation.setupEnabled
        embeddedGuardianStopButton.text = presentation.stopButtonText
        embeddedGuardianStopButton.isEnabled = presentation.stopEnabled
        embeddedGuardianCycleButton.isEnabled = presentation.privilegedOperationsEnabled
        embeddedGuardianGmsButton.isEnabled = presentation.privilegedOperationsEnabled
        embeddedGuardianSummary.text = presentation.summary +
            snapshot.lastError.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
        embeddedGuardianSummary.setTextColor(
            when {
                snapshot.liveConnected -> palette().good
                snapshot.featureEnabled -> palette().warning
                else -> palette().secondary
            }
        )
    }

    private fun formatPrivilegedGuardianSummary(snapshot: PrivilegedGuardianSnapshot): String = when {
        !snapshot.shizukuAvailable ->
            "未发现正在运行的 Shizuku / Sui。它仅作为 2.1 内置引擎不可用时的兼容后备。"
        !snapshot.permissionGranted ->
            "Shizuku / Sui 已运行，但努昂诺塔尚未获得 shell/root UserService 权限。"
        snapshot.running && snapshot.privileged -> {
            val mode = if (snapshot.root) "root UID 0" else "shell UID 2000"
            val age = snapshot.statusAgeMs?.let(::privilegedStatusAge) ?: "未知"
            "运行中 · $mode · sticky=${if (snapshot.stickySupported) "支持" else "不可用"} · " +
                "事件监听=${if (snapshot.eventWatcherAlive) "在线" else "离线"}(${snapshot.eventTriggerCount}) · " +
                "厂商冻结 ${snapshot.vendorSignalCount} / 投递拒绝 ${snapshot.vendorDeliveryFailureCount} / " +
                "恢复轮 ${snapshot.vendorRecoveryPassCount} · " +
                "WA重建 ${snapshot.packageRebuildSuccessCount}/${snapshot.packageRebuildAttemptCount} · " +
                "目标进程 ${snapshot.processCount} · 冻结证据 ${snapshot.frozenProcessCount} · " +
                "周期 ${snapshot.cycleCount} / 动作 ${snapshot.actionCount} / 错误 ${snapshot.errorCount} · 状态 $age"
        }
        snapshot.configuredEnabled ->
            "已启用但尚未建立特权执行层 · 状态 ${snapshot.connectionState}" +
                snapshot.lastError.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
        else ->
            "已就绪但未启用。开启后将创建独立 daemon UserService，并立即执行首轮。"
    }

    private fun privilegedStatusAge(ageMs: Long): String = when {
        ageMs < 2_000L -> "刚刚"
        ageMs < 60_000L -> "${ageMs / 1_000} 秒前"
        ageMs < 3_600_000L -> "${ageMs / 60_000} 分钟前"
        else -> "${ageMs / 3_600_000} 小时前"
    }

    private fun toggleScreenOffCpuGuard() {
        val status = readGuardianStatus()

        if (
            !status.containsKey(
                LuonnotarPreferences.KEY_KEEPER_PROCESS_PID
            )
        ) {
            showGlassNotice(
                "Keeper 状态不可读，无法修改 CPU Guard",
                long = true
            )
            return
        }

        if (
            guardianProfile(status) ==
            GuardianRuntimeProfile.ADB_PASSIVE
        ) {
            showGlassNotice(
                "ADB 一次性验证必须保持无连续锁基线"
            )
            return
        }

        val enabled = status.boolean(
            LuonnotarPreferences
                .KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD
        )
        val newValue = !enabled

        if (
            !GuardianStatusClient.setExperiment(
                this,
                LuonnotarPreferences
                    .KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD,
                newValue
            )
        ) {
            showGlassNotice(
                "熄屏 CPU Guard 写入失败",
                long = true
            )
            return
        }

        LogManager.event(
            this,
            "screen_off_cpu_guard_config_changed",
            mapOf("enabled" to newValue)
        )

        if (
            status.boolean(LuonnotarPreferences.KEY_ENABLED) &&
            !status.boolean(LuonnotarPreferences.KEY_PAUSED)
        ) {
            runCatching {
                startService(
                    Intent(this, FcmGuardianService::class.java)
                        .setAction(
                            FcmGuardianService.ACTION_PROFILE_CHANGED
                        )
                )
            }.onFailure {
                showGlassNotice(
                    "配置已保存，但守护即时重算失败：${
                        it.javaClass.simpleName
                    }",
                    long = true
                )
            }
        }

        showGlassNotice(
            if (newValue) {
                "熄屏 CPU Guard 已开启；亮屏时不会持有连续锁"
            } else {
                "熄屏 CPU Guard 已关闭"
            }
        )
        renderStatus()
    }

    private fun updateCpuGuardUi(
        status: Bundle,
        serviceAlive: Boolean,
        screenInteractive: Boolean,
        continuousWakeLockHeld: Boolean
    ) {
        if (
            !::cpuGuardSummary.isInitialized ||
            !::cpuGuardButton.isInitialized
        ) return

        val profile = guardianProfile(status)
        val configured = status.boolean(
            LuonnotarPreferences
                .KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD
        )
        val passive =
            profile == GuardianRuntimeProfile.ADB_PASSIVE
        val statusAvailable = status.containsKey(
            LuonnotarPreferences.KEY_KEEPER_PROCESS_PID
        )

        cpuGuardButton.isEnabled =
            statusAvailable && !passive
        cpuGuardButton.text =
            if (configured) {
                "关闭熄屏 CPU Guard"
            } else {
                "开启熄屏 CPU Guard"
            }

        cpuGuardSummary.text = when {
            passive ->
                "ADB 一次性验证 · 固定关闭连续 CPU Lock"
            !configured ->
                "关闭 · 不影响任务级 10 秒 CPU Lock"
            !serviceAlive ->
                "已配置 · 守护未运行，当前未持有连续锁"
            screenInteractive && continuousWakeLockHeld ->
                "已配置 · 当前亮屏，但连续锁仍持有，等待策略释放"
            screenInteractive ->
                "已配置 · 当前亮屏，连续锁已释放"
            continuousWakeLockHeld ->
                "已配置 · 当前熄屏，连续锁已持有"
            else ->
                "已配置 · 当前熄屏，但连续锁尚未持有"
        }

        cpuGuardSummary.setTextColor(
            when {
                passive || !configured ->
                    palette().secondary
                !serviceAlive ->
                    palette().warning
                screenInteractive &&
                    !continuousWakeLockHeld ->
                    palette().good
                !screenInteractive &&
                    continuousWakeLockHeld ->
                    palette().good
                else ->
                    palette().warning
            }
        )
    }

    private fun toggleGmsBinderAnchor() {
        val status = readGuardianStatus()
        val enabled = status.boolean(
            LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_ENABLED
        )
        val newValue = !enabled
        if (!GuardianStatusClient.setGmsBinderAnchorEnabled(this, newValue)) {
            showGlassNotice("实验开关写入失败", long = true)
            return
        }
        if (
            status.boolean(LuonnotarPreferences.KEY_ENABLED) &&
            !status.boolean(LuonnotarPreferences.KEY_PAUSED)
        ) {
            runCatching {
                startService(
                    Intent(this, FcmGuardianService::class.java)
                        .setAction(
                            FcmGuardianService.ACTION_GMS_BINDER_ANCHOR_CHANGED
                        )
                )
            }.onFailure {
                showGlassNotice(
                    "配置已保存，但 Anchor 即时重载失败：${it.javaClass.simpleName}",
                    long = true
                )
            }
        }
        showGlassNotice(
            if (newValue) {
                "GMS Binder Anchor 已开启；将在守护运行时由 :keeper 持有"
            } else {
                "GMS Binder Anchor 已关闭"
            }
        )
        renderStatus()
    }

    private fun requestGmsBinderAnchorRetry() {
        val status = readGuardianStatus()
        if (!status.boolean(LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_ENABLED)) {
            showGlassNotice("请先开启 GMS Binder Anchor")
            return
        }
        if (
            !status.boolean(LuonnotarPreferences.KEY_ENABLED) ||
            status.boolean(LuonnotarPreferences.KEY_PAUSED)
        ) {
            showGlassNotice("守护未运行；Anchor 会在守护恢复后自动连接")
            return
        }
        runCatching {
            startService(
                Intent(this, FcmGuardianService::class.java)
                    .setAction(
                        FcmGuardianService.ACTION_GMS_BINDER_ANCHOR_RETRY
                    )
            )
        }.onSuccess {
            showGlassNotice("已请求 :keeper 重新连接 GMS Binder Anchor")
        }.onFailure {
            showGlassNotice(
                "重新连接请求失败：${it.javaClass.simpleName}",
                long = true
            )
        }
    }

    private fun requestGmsBinderPulseTest() {
        val status = readGuardianStatus()
        if (
            !status.boolean(LuonnotarPreferences.KEY_ENABLED) ||
            status.boolean(LuonnotarPreferences.KEY_PAUSED)
        ) {
            showGlassNotice("请先启动守护；脉冲必须由 :keeper 进程执行")
            return
        }
        runCatching {
            startService(
                Intent(this, FcmGuardianService::class.java)
                    .setAction(
                        FcmGuardianService.ACTION_GMS_BINDER_PULSE_TEST
                    )
            )
        }.onSuccess {
            showGlassNotice(
                "已请求 :keeper 执行 15 秒 GMS Binder 脉冲；期间发送测试消息"
            )
        }.onFailure {
            showGlassNotice(
                "GMS Binder 脉冲请求失败：${it.javaClass.simpleName}",
                long = true
            )
        }
    }

    private fun updateGmsAnchorUi(status: Bundle) {
        if (!::gmsAnchorSummary.isInitialized) return
        val enabled = status.boolean(
            LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_ENABLED
        )
        val state = runCatching {
            GmsBinderAnchorState.valueOf(
                status.string(
                    LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_STATE,
                    GmsBinderAnchorState.DISABLED.name
                )
            )
        }.getOrDefault(GmsBinderAnchorState.DISABLED)
        val keeperPid = status.integer(
            LuonnotarPreferences.KEY_KEEPER_PROCESS_PID
        )
        val anchorPid = status.integer(
            LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_PID
        )
        val anchorFresh =
            keeperPid > 0 &&
                anchorPid == keeperPid &&
                status.string(LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_BOOT_ID) ==
                bootId
        gmsAnchorSummary.text = when {
            !enabled -> "关闭 · 不影响正常守护"
            state == GmsBinderAnchorState.WAITING_FOR_GUARDIAN ->
                "已开启 · 等待守护运行后由 :keeper 建立连接"
            !anchorFresh ->
                "Anchor 状态已过期，等待 :keeper 重新建立"
            state == GmsBinderAnchorState.CONNECTING ->
                ":keeper 正在建立公开 Google Play services Binder 连接"
            state == GmsBinderAnchorState.CONNECTED ->
                "Binder 已由 :keeper 持有 · 不代表 GMS MCS/FCM 已确认"
            state == GmsBinderAnchorState.FAILED ||
                state == GmsBinderAnchorState.RETRY_EXHAUSTED ->
                "连接失败 · failureCode ${status.integer(LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_FAILURE_CODE)} · reconnectAttempt ${status.integer(LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_RECONNECT_ATTEMPT)}"
            else -> "${state.name} · 不代表 FCM 已确认"
        }
        gmsAnchorButton.text =
            if (enabled) {
                "关闭 GMS Binder Anchor"
            } else {
                "开启 GMS Binder Anchor"
            }
        gmsAnchorRetryButton.isEnabled = enabled
        if (::gmsPulseTestButton.isInitialized) {
            gmsPulseTestButton.isEnabled =
                status.boolean(LuonnotarPreferences.KEY_ENABLED) &&
                    !status.boolean(LuonnotarPreferences.KEY_PAUSED)
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
        val freshness = AdbVpnEvidencePolicy.freshness(
                verifiedElapsed = verifiedElapsed,
                nowElapsed = SystemClock.elapsedRealtime(),
                verifiedBootId = verifiedBootId,
                currentBootId = currentBootId(),
                activePackage = activePackage,
                evidenceHash = evidenceHash,
                verifiedNetworkHandle = verifiedNetworkHandle,
                currentNetworkHandle = currentNetworkHandle,
                vpnPresent = status.boolean(LuonnotarPreferences.KEY_VPN),
                verifiedSessionFingerprint = status.string(
                    LuonnotarPreferences.KEY_ADB_SESSION_FINGERPRINT
                ),
                currentSessionFingerprint = status.string(
                    LuonnotarPreferences.KEY_VPN_SESSION_FINGERPRINT
                ),
                currentProviderPackage = status.string(
                    LuonnotarPreferences.KEY_VPN_PROVIDER_PACKAGE
                )
            )
        if (freshness == AdbVpnEvidencePolicy.Freshness.INVALID) {
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
            evidenceHash = evidenceHash,
            stale = freshness == AdbVpnEvidencePolicy.Freshness.STALE
        )
    }

    private fun verificationAge(verification: AdbVpnVerification): String {
        val minutes = ((SystemClock.elapsedRealtime() - verification.verifiedElapsed) / 60_000)
            .coerceAtLeast(0)
        return when {
            verification.stale -> "${minutes / 60}小时前 · STALE"
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

    private fun isActiveUserTarget(target: String): Boolean {
        if (Process.myUid() / 100_000 != 0) return false
        return runCatching {
            @Suppress("DEPRECATION")
            val info = packageManager.getApplicationInfo(
                target,
                PackageManager.MATCH_DISABLED_COMPONENTS
            )
            info.enabled &&
                (info.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) == 0 &&
                (info.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) == 0
        }.getOrDefault(false)
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
        glassButtons += this
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
        return if (dark || imageContrast) {
            Palette(
                foreground = 0xFFF7F7F9.toInt(),
                secondary = if (imageContrast) 0xFFE2E8EE.toInt() else 0xFFB5BAC3.toInt(),
                good = 0xFF30D158.toInt(),
                warning = 0xFFFFD60A.toInt()
            )
        } else {
            Palette(
                foreground = 0xFF10212C.toInt(),
                secondary = 0xFF425968.toInt(),
                good = 0xFF0E6B48.toInt(),
                warning = 0xFF8A5400.toInt()
            )
        }
    }

    private fun usesImageContrast(): Boolean =
        VisualPreferences.load(this).background != BackgroundPreference.SOLID

    private fun applyImageBackgroundTextContrast(view: View) {
        when (view) {
            is TextView -> view.setShadowLayer(
                resources.displayMetrics.density * 1.05f,
                0f,
                resources.displayMetrics.density * 0.45f,
                0xA8000000.toInt()
            )
            is ViewGroup -> for (index in 0 until view.childCount) {
                applyImageBackgroundTextContrast(view.getChildAt(index))
            }
        }
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
                .setUpdateListener {
                    if (::visualBackground.isInitialized) {
                        visualBackground.invalidateSurfacePositions()
                    }
                }
                .start()
        }
    }

    private data class StatusViews(
        val row: View,
        val value: TextView
    )

    private data class StatusDetail(
        val value: String,
        val good: Boolean
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
        val evidenceHash: String,
        val stale: Boolean
    )

    private data class Palette(
        val foreground: Int,
        val secondary: Int,
        val good: Int,
        val warning: Int
    )

    companion object {
        private val CORE_STATUS_LABELS = setOf(
            "守护服务",
            "当前 VPN Provider",
            "Tailscale 引擎",
            "VPN 会话健康",
            "真实 FCM Canary",
            "自动恢复异常"
        )

        const val EXTRA_STATUS_MESSAGE = "status_message"
        const val EXTRA_OPEN_VPN_CHOOSER = "open_vpn_chooser"
        const val EXTRA_SCROLL_TO_EMBEDDED_GUARDIAN =
            "scroll_to_embedded_guardian"
        const val EXTRA_EMBEDDED_NOTIFICATION_SOURCE =
            "embedded_notification_source"
        const val EXTRA_EMBEDDED_NOTIFICATION_BOOT_ACTION =
            "embedded_notification_boot_action"
        const val VISIBLE_RECOVERY_COOLDOWN_MS = 15_000L
        const val KEEPALIVE_SUCCESS_FRESH_MS = 10 * 60_000L
        const val TAILSCALE_DNS_FRESH_MS = 90_000L
        const val ENVIRONMENT_INSPECTION_INTERVAL_MS = 30_000L
    }
}
