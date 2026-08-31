package com.yubegreen.luonnotar.ui.visual

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.yubegreen.luonnotar.service.GuardianExperimentSettings
import com.yubegreen.luonnotar.service.GuardianProfilePolicy
import com.yubegreen.luonnotar.service.GuardianRuntimeProfile
import com.yubegreen.luonnotar.ui.motion.GuardianActionButton
import com.yubegreen.luonnotar.ui.i18n.UiText
import com.yubegreen.luonnotar.util.LuonnotarPreferences

class GuardianExperimentDialog(
    context: Context,
    preferences: VisualPreferences,
    visualBackground: VisualBackgroundView?,
    anchorView: View?,
    initialProfile: GuardianRuntimeProfile,
    initialLevel: Int,
    initialValues: Map<String, Boolean>,
    private val onApply: (
        GuardianRuntimeProfile,
        Int,
        Map<String, Boolean>
    ) -> Unit
) : GlassSheetDialog(
    context,
    preferences,
    visualBackground,
    anchorView
) {
    private var selectedProfile = initialProfile
    private var selectedLevel = initialLevel.coerceIn(0, 4)
    private val values = initialValues.toMutableMap()
    private val profileButtons =
        linkedMapOf<GuardianRuntimeProfile, GuardianActionButton>()
    private val levelButtons = linkedMapOf<Int, GuardianActionButton>()
    private val experimentButtons = linkedMapOf<String, GuardianActionButton>()
    private val labOnlyKeys =
        GuardianProfilePolicy.labOnlyExperimentKeys
    private val passiveDisabledKeys =
        GuardianProfilePolicy.passiveDisabledExperimentKeys

    private val definitions = listOf(
        LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD to
            "熄屏保持 CPU 活跃",
        LuonnotarPreferences.KEY_EXPERIMENT_HIGH_PERF_WIFI_LOCK to
            "息屏保持高性能 Wi‑Fi",
        LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_EVENT_PROBE to
            "屏幕事件探测",
        LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_DNS to
            "周期 VPN DNS",
        LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_HTTPS to
            "周期 HTTPS 204",
        LuonnotarPreferences.KEY_EXPERIMENT_AUTOMATIC_MTALK to
            "自动 mtalk 诊断",
        LuonnotarPreferences.KEY_EXPERIMENT_PERSISTENT_NETWORK_LEASE to
            "持续 VPN NetworkRequest",
        LuonnotarPreferences.KEY_EXPERIMENT_PERSISTENT_HEARTBEAT_SOCKET to
            "持续 HTTPS 心跳通道（实验）",
        LuonnotarPreferences.KEY_EXPERIMENT_FREQUENT_NOTIFICATION_REFRESH to
            "频繁刷新常驻通知",
        LuonnotarPreferences.KEY_EXPERIMENT_PERMANENT_CPU_LOCK to
            "永久 CPU Lock（仅实验室 L4）",
        LuonnotarPreferences.KEY_MONITOR_GMS to
            "监控 Google Play 服务",
        LuonnotarPreferences.KEY_MONITOR_WHATSAPP to
            "监控 WhatsApp",
        LuonnotarPreferences.KEY_MONITOR_WHATSAPP_BUSINESS to
            "监控 WhatsApp Business"
    )

    init {
        content.addView(title("守护功能开关"))
        content.addView(
            body(
                "核心守护默认启用息屏 CPU 保活；" +
                    "Wi-Fi Lock 与网络探测仍可独立控制。" +
                    "ADB 一次性验证固定禁用持续锁和主动探测。"
            ),
            margin(top = 8)
        )
        content.addView(caption("运行模式"), margin(top = 14))
        val profiles = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(
            GuardianRuntimeProfile.STANDARD to "标准",
            GuardianRuntimeProfile.IQOO_COOPERATIVE to "自适应",
            GuardianRuntimeProfile.ADB_PASSIVE to "ADB",
            GuardianRuntimeProfile.LAB_EXTREME to "实验室"
        ).forEachIndexed { index, (profile, label) ->
            val option = button(label).apply {
                setOnClickListener {
                    selectedProfile = profile
                    if (
                        profile != GuardianRuntimeProfile.LAB_EXTREME
                    ) {
                        applySettings(
                            GuardianProfilePolicy.defaults(profile)
                        )
                    }
                    refresh()
                }
            }
            profileButtons[profile] = option
            profiles.addView(option, weighted(index, 4))
        }
        content.addView(profiles, margin(top = 7))
        content.addView(
            caption("实验室分级预设（选择后仍可逐项修改）"),
            margin(top = 14)
        )
        val levels = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        (0..4).forEachIndexed { index, level ->
            val option = button("L$level").apply {
                setOnClickListener {
                    selectedProfile = GuardianRuntimeProfile.LAB_EXTREME
                    selectedLevel = level
                    applySettings(GuardianProfilePolicy.labLevel(level))
                    refresh()
                }
            }
            levelButtons[level] = option
            levels.addView(option, weighted(index, 5))
        }
        content.addView(levels, margin(top = 7))

        val options = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        definitions.forEach { (key, label) ->
            val option = button(label).apply {
                setOnClickListener {
                    values[key] = !(values[key] ?: false)
                    refresh()
                }
            }
            experimentButtons[key] = option
            options.addView(
                option,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            )
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(options)
        }
        content.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (context.resources.displayMetrics.heightPixels * 0.43f).toInt()
            ).apply { topMargin = dp(10) }
        )
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(
            button("取消").apply { setOnClickListener { dismiss() } },
            weighted(0)
        )
        actions.addView(
            button("应用", emphasized = true).apply {
                setOnClickListener {
                    val profile = selectedProfile
                    val level = selectedLevel
                    val snapshot = values.toMap()
                    dismissThen { onApply(profile, level, snapshot) }
                }
            },
            weighted(1)
        )
        content.addView(actions, margin(top = 14))
        refresh()
    }

    private fun applySettings(settings: GuardianExperimentSettings) {
        values[LuonnotarPreferences.KEY_EXPERIMENT_PERMANENT_CPU_LOCK] =
            settings.permanentCpuLock
        values[
            LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD
        ] = settings.screenOffCpuGuard
        values[LuonnotarPreferences.KEY_EXPERIMENT_SCOPED_CPU_LOCK] =
            settings.scopedCpuLock
        values[LuonnotarPreferences.KEY_EXPERIMENT_HIGH_PERF_WIFI_LOCK] =
            settings.highPerfWifiLock
        values[LuonnotarPreferences.KEY_EXPERIMENT_SCREEN_EVENT_PROBE] =
            settings.screenEventProbe
        values[LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_DNS] =
            settings.periodicDns
        values[LuonnotarPreferences.KEY_EXPERIMENT_PERIODIC_HTTPS] =
            settings.periodicHttps
        values[LuonnotarPreferences.KEY_EXPERIMENT_AUTOMATIC_MTALK] =
            settings.automaticMtalk
        values[LuonnotarPreferences.KEY_EXPERIMENT_PERSISTENT_NETWORK_LEASE] =
            settings.persistentNetworkLease
        values[LuonnotarPreferences.KEY_EXPERIMENT_PERSISTENT_HEARTBEAT_SOCKET] =
            settings.persistentHeartbeatSocket
        values[
            LuonnotarPreferences.KEY_EXPERIMENT_FREQUENT_NOTIFICATION_REFRESH
        ] = settings.frequentNotificationRefresh
    }

    private fun refresh() {
        profileButtons.forEach { (profile, button) ->
            style(button, profile == selectedProfile)
        }
        levelButtons.forEach { (level, button) ->
            button.isEnabled =
                selectedProfile == GuardianRuntimeProfile.LAB_EXTREME
            style(
                button,
                selectedProfile == GuardianRuntimeProfile.LAB_EXTREME &&
                    selectedLevel == level
            )
        }
        definitions.forEach { (key, label) ->
            val enabled = values[key] == true
            experimentButtons[key]?.let { button ->
                button.isEnabled =
                    (
                        selectedProfile ==
                            GuardianRuntimeProfile.LAB_EXTREME ||
                            key !in labOnlyKeys
                        ) &&
                        (
                            selectedProfile !=
                                GuardianRuntimeProfile.ADB_PASSIVE ||
                                key !in passiveDisabledKeys
                            )
                val displayLabel = UiText.localize(context, label)
                button.text = UiText.choose(
                    context,
                    "$displayLabel：${if (enabled) "开" else "关"}",
                    "$displayLabel: ${if (enabled) "On" else "Off"}"
                )
                button.contentDescription = UiText.choose(
                    context,
                    "$displayLabel，当前${if (enabled) "开启" else "关闭"}",
                    "$displayLabel, currently ${if (enabled) "on" else "off"}"
                )
                style(button, enabled)
            }
        }
    }

    private fun style(button: GuardianActionButton, selected: Boolean) {
        button.isSelected = selected
        (button.background as? LiquidGlassDrawable)?.setSelected(selected)
        button.setTextColor(
            if (selected) palette.accentText else palette.foreground
        )
    }

    private fun weighted(index: Int, count: Int = 2) =
        LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            if (index > 0) marginStart = dp(3)
            if (index < count - 1) marginEnd = dp(3)
        }

    private fun margin(top: Int) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }
}
