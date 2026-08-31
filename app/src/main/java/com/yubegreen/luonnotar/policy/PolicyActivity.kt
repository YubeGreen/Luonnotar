package com.yubegreen.luonnotar.policy

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.yubegreen.luonnotar.MainActivity
import com.yubegreen.luonnotar.R
import com.yubegreen.luonnotar.service.FcmGuardianService
import com.yubegreen.luonnotar.service.GuardianStatusClient
import com.yubegreen.luonnotar.ui.motion.GuardianActionButton
import com.yubegreen.luonnotar.ui.i18n.AppLanguage
import com.yubegreen.luonnotar.ui.i18n.AppLanguageStore
import com.yubegreen.luonnotar.ui.i18n.UiText
import com.yubegreen.luonnotar.ui.visual.AdaptiveLayout
import com.yubegreen.luonnotar.ui.visual.AdaptiveMaxWidthLinearLayout
import com.yubegreen.luonnotar.ui.visual.BackgroundPreference
import com.yubegreen.luonnotar.ui.visual.LiquidGlassDrawable
import com.yubegreen.luonnotar.ui.visual.LiquidGlassPanel
import com.yubegreen.luonnotar.ui.visual.VisualBackgroundView
import com.yubegreen.luonnotar.ui.visual.VisualPreferences
import com.yubegreen.luonnotar.worker.FcmRecoveryWorker

class PolicyActivity : AppCompatActivity() {
    private lateinit var agree: GuardianActionButton
    private lateinit var check: AppCompatCheckBox
    private lateinit var policyScroll: ScrollView
    private lateinit var readHint: TextView
    private lateinit var visualBackground: VisualBackgroundView
    private var readToEnd = false
    private var restoredScrollY = 0
    private var restoredChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = VisualPreferences.nightMode(VisualPreferences.load(this).theme)
        super.onCreate(savedInstanceState)
        if (PolicyManager.isAccepted(this)) {
            continueToMain()
            return
        }
        readToEnd = savedInstanceState?.getBoolean(STATE_READ_TO_END) ?: false
        restoredScrollY = savedInstanceState?.getInt(STATE_SCROLL_Y) ?: 0
        restoredChecked = savedInstanceState?.getBoolean(STATE_CHECKED) ?: false
        configureSystemBars()
        setContentView(buildContent())
        policyScroll.post {
            policyScroll.scrollTo(0, restoredScrollY)
            updatePolicyReadState(policyScroll, policyScroll.scrollY)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_READ_TO_END, readToEnd)
        outState.putBoolean(STATE_CHECKED, ::check.isInitialized && check.isChecked)
        outState.putInt(STATE_SCROLL_Y, if (::policyScroll.isInitialized) policyScroll.scrollY else 0)
        super.onSaveInstanceState(outState)
    }

    private fun buildContent(): View {
        val colors = colors()
        val preferences = VisualPreferences.load(this)
        val tablet = AdaptiveLayout.isTablet(this)
        val contentEdge = if (tablet) 24 else 18
        visualBackground = VisualBackgroundView(this).apply { apply(preferences) }
        val column = AdaptiveMaxWidthLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            maximumWidthPx = AdaptiveLayout.contentMaximumWidthPx(this@PolicyActivity)
            setPadding(dp(contentEdge), dp(if (tablet) 28 else 16), dp(contentEdge), dp(18))
        }
        column.addView(text("语言 / Language", if (tablet) 15f else 12f, colors.second, false).apply {
            setPadding(dp(2), 0, dp(2), dp(6))
        })
        val languageRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val currentLanguage = AppLanguageStore.current(this)
        val chineseButton = button("简体中文", colors.first) { changeLanguage(AppLanguage.CHINESE) }
        val englishButton = button("English", colors.first) { changeLanguage(AppLanguage.ENGLISH) }
        styleLanguageButton(chineseButton, currentLanguage == AppLanguage.CHINESE)
        styleLanguageButton(englishButton, currentLanguage == AppLanguage.ENGLISH)
        languageRow.addView(
            chineseButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) }
        )
        languageRow.addView(
            englishButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) }
        )
        column.addView(
            languageRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        )
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@PolicyActivity).apply {
                setImageResource(R.mipmap.ic_luonnotar)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(14) })
            addView(LinearLayout(this@PolicyActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(tr("努昂诺塔", "Luonnotar"), if (tablet) 31f else 25f, colors.first, true))
                addView(
                    text(
                        tr(
                            "首次启动政策告示 · v${PolicyManager.VERSION}",
                            "First-launch policy notice · v${PolicyManager.VERSION}"
                        ),
                        if (tablet) 16f else 13f,
                        colors.second,
                        false
                    )
                )
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        val policyText = text(
            PolicyManager.text(this),
            if (tablet) 17f else 14f,
            colors.first,
            false
        ).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(16), dp(16), dp(16), dp(18))
        }
        val policyPanel = LiquidGlassPanel(this, dp(20).toFloat(), usesImageContrast()).apply {
            setTouchFeedbackEnabled(false)
            addView(policyText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        policyScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(policyPanel)
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                updatePolicyReadState(this, scrollY)
                visualBackground.invalidateSurfacePositions()
            }
        }
        policyScroll.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            updatePolicyReadState(view as ScrollView, view.scrollY)
        }
        policyText.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            policyScroll.post {
                updatePolicyReadState(policyScroll, policyScroll.scrollY)
            }
        }
        column.addView(policyScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        readHint = text(
            tr(
                "请滑动阅读至末尾后确认。政策更新时会重新要求确认。",
                "Scroll to the end before confirming. You will be asked again when the policy changes."
            ),
            if (tablet) 15f else 12f,
            colors.second,
            false
        ).apply {
            setPadding(dp(4), dp(10), dp(4), dp(4))
        }
        column.addView(readHint)
        check = AppCompatCheckBox(this).apply {
            text = tr("我已阅读并同意《努昂诺塔使用政策与隐私说明》", "I have read and agree to the Luonnotar Terms of Use and Privacy Notice")
            textSize = if (tablet) 16f else 13f
            setTextColor(colors.first)
            isChecked = restoredChecked
            setOnCheckedChangeListener { _, _ -> refreshAgreement() }
        }
        column.addView(check)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button(tr("不同意并退出", "Disagree and exit"), colors.first) { rejectAndExit() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(5)
        })
        agree = button(tr("同意并继续", "Agree and continue"), colors.first) {
            PolicyManager.accept(this)
            continueToMain()
        }
        actions.addView(agree, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) })
        column.addView(actions)
        refreshAgreement()

        val root = FrameLayout(this).apply {
            addView(visualBackground, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                column,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER_HORIZONTAL
                )
            )
        }
        val topFade = View(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
        }
        val bottomFade = View(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
        }
        root.addView(
            topFade,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP)
        )
        root.addView(
            bottomFade,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM)
        )
        policyPanel.bindBackground(visualBackground)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            column.setPadding(
                dp(contentEdge),
                dp(if (tablet) 28 else 16) + safe.top,
                dp(contentEdge),
                dp(18) + safe.bottom
            )
            (column.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.leftMargin = safe.left
                params.rightMargin = safe.right
                column.layoutParams = params
            }
            updateSystemBarFades(topFade, bottomFade, safe.top, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        return root
    }

    private fun rejectAndExit() {
        PolicyManager.reject(this)
        GuardianStatusClient.rejectPolicy(this)
        runCatching {
            startService(
                Intent(this, FcmGuardianService::class.java)
                    .setAction(FcmGuardianService.ACTION_STOP)
                    .putExtra(FcmGuardianService.EXTRA_START_REASON, "policy_rejected")
            )
        }
        runCatching { FcmRecoveryWorker.cancelAll(this) }
        finishAffinity()
    }

    private fun updateSystemBarFades(
        topFade: View,
        bottomFade: View,
        statusHeight: Int,
        navigationHeight: Int
    ) {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val imageContrast = usesImageContrast()
        val edge = when {
            imageContrast -> 0x52000000
            dark -> 0x7007090D
            else -> 0x4CF6F8FC
        }
        topFade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(edge, Color.TRANSPARENT)
        )
        bottomFade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, edge)
        )
        topFade.layoutParams = (topFade.layoutParams as FrameLayout.LayoutParams).apply {
            height = statusHeight + dp(34)
        }
        bottomFade.layoutParams =
            (bottomFade.layoutParams as FrameLayout.LayoutParams).apply {
                height = navigationHeight + dp(44)
            }
    }

    private fun updatePolicyReadState(scrollView: ScrollView, scrollY: Int) {
        val child = scrollView.getChildAt(0)
        val reached = child != null && PolicyReadGate.isAtEnd(
            scrollY = scrollY,
            viewportHeight = scrollView.height,
            contentHeight = child.height,
            tolerancePx = dp(12)
        )
        val newlyReached = reached && !readToEnd
        readToEnd = readToEnd || reached
        if (newlyReached) {
            scrollView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            scrollView.announceForAccessibility(tr("政策已阅读至末尾，现在可以勾选同意。", "You have reached the end of the policy. You can now tick the agreement box."))
        }
        refreshAgreement()
    }

    private fun refreshAgreement() {
        if (!::agree.isInitialized || !::check.isInitialized) return
        check.isEnabled = readToEnd
        agree.isEnabled = readToEnd && check.isChecked
        agree.alpha = if (agree.isEnabled) 1f else 0.42f
        agree.contentDescription = if (agree.isEnabled) {
            tr("同意并继续", "Agree and continue")
        } else {
            tr("请先阅读至末尾并勾选同意", "Read to the end and tick the agreement box first")
        }
        if (::readHint.isInitialized) {
            readHint.text = if (readToEnd) {
                tr("已阅读至末尾，可以勾选确认。", "You have reached the end. You can now tick the agreement box.")
            } else {
                tr(
                    "请滑动阅读至末尾后确认。政策更新时会重新要求确认。",
                    "Scroll to the end before confirming. You will be asked again when the policy changes."
                )
            }
        }
    }

    private fun continueToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
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

    private fun colors(): Pair<Int, Int> =
        if (usesImageContrast()) 0xFFF7F7F9.toInt() to 0xFFE8E8ED.toInt()
        else if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
            0xFFF5F5F7.toInt() to 0xFFA1A1A6.toInt()
        } else {
            0xFF142128.toInt() to 0xFF465B66.toInt()
        }

    private fun usesImageContrast(): Boolean {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        return !dark && VisualPreferences.load(this).background != BackgroundPreference.SOLID
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = UiText.localize(this@PolicyActivity, value)
        textSize = size
        setTextColor(color)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, color: Int, action: () -> Unit) = GuardianActionButton(this).apply {
        text = UiText.localize(this@PolicyActivity, label)
        isAllCaps = false
        textSize = if (AdaptiveLayout.isTablet(this@PolicyActivity)) 16.5f else 13f
        minHeight = dp(if (AdaptiveLayout.isTablet(this@PolicyActivity)) 56 else 48)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setTextColor(color)
        background = LiquidGlassDrawable(this@PolicyActivity, dp(17).toFloat(), usesImageContrast())
        if (::visualBackground.isInitialized) bindGlassBackground(visualBackground)
        setOnClickListener { action() }
    }

    private fun tr(zh: String, en: String): String =
        UiText.choose(this, zh, en).toString()

    private fun changeLanguage(language: AppLanguage) {
        if (AppLanguageStore.current(this) == language) return
        AppLanguageStore.set(this, language)
        recreate()
    }

    private fun styleLanguageButton(button: GuardianActionButton, selected: Boolean) {
        button.isSelected = selected
        button.typeface = if (selected) {
            android.graphics.Typeface.DEFAULT_BOLD
        } else {
            android.graphics.Typeface.DEFAULT
        }
        (button.background as? LiquidGlassDrawable)?.setSelected(selected)
        button.alpha = if (selected) 1f else 0.78f
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val STATE_READ_TO_END = "policy_read_to_end"
        const val STATE_CHECKED = "policy_checked"
        const val STATE_SCROLL_Y = "policy_scroll_y"
    }
}
