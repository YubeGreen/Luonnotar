package com.yubegreen.luonnotar.ui.visual

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.yubegreen.luonnotar.ui.motion.GuardianActionButton

class VisualSettingsDialog(
    context: Context,
    current: VisualPreferences,
    visualBackground: VisualBackgroundView?,
    anchorView: View?,
    private val onCustomRequested: (ThemePreference, BackgroundScale) -> Unit,
    private val onApply: (ThemePreference, BackgroundPreference, BackgroundScale) -> Unit
) : GlassSheetDialog(context, current, visualBackground, anchorView) {
    private val tabletDialog = AdaptiveLayout.isTablet(context)
    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    private val foreground = palette.foreground
    private val secondary = palette.secondary
    private val themeButtons = linkedMapOf<ThemePreference, GuardianActionButton>()
    private val backgroundButtons = linkedMapOf<BackgroundPreference, GuardianActionButton>()
    private val scaleButtons = linkedMapOf<BackgroundScale, GuardianActionButton>()
    private lateinit var customHint: TextView
    private var selectedTheme =
        if (current.background == BackgroundPreference.SHAO_OU) ThemePreference.DARK else current.theme
    private var selectedBackground = current.background
    private var selectedScale = current.backgroundScale

    init {
        buildContent()
        refreshSelection()
    }

    private fun buildContent() {
        content.addView(label("外观", if (tabletDialog) 25f else 20f, true))
        content.addView(label("所有背景均保持静态，不影响后台守护", if (tabletDialog) 17f else 13f, false))
        content.addView(sectionCaption("界面主题"))
        content.addView(optionRow(
            listOf(
                ThemePreference.DARK to "深色",
                ThemePreference.LIGHT to "浅色",
                ThemePreference.SYSTEM to "跟随系统"
            ),
            themeButtons
        ) {
            selectedTheme = it
            refreshSelection()
        })
        content.addView(sectionCaption("背景"))
        content.addView(optionRow(
            listOf(
                BackgroundPreference.SOLID to "纯色",
                BackgroundPreference.SHAO_OU to "少偶",
                BackgroundPreference.CUSTOM_IMAGE to "自定义…"
            ),
            backgroundButtons
        ) {
            selectedBackground = it
            if (it == BackgroundPreference.SHAO_OU) {
                selectedTheme = ThemePreference.DARK
                selectedScale = BackgroundScale.FILL_CROP
            }
            refreshSelection()
            if (it == BackgroundPreference.CUSTOM_IMAGE) {
                val theme = selectedTheme
                val scale = selectedScale
                dismissThen { onCustomRequested(theme, scale) }
            }
        })
        content.addView(sectionCaption("图片显示方式"))
        content.addView(optionRow(
            listOf(
                BackgroundScale.FILL_CROP to "填充裁切",
                BackgroundScale.FIT_CENTER to "完整显示"
            ),
            scaleButtons
        ) {
            selectedScale = it
            refreshSelection()
        })
        customHint = label("选择自定义后打开系统图库；图片只保存在本机", if (tabletDialog) 15.5f else 12f, false).apply {
            setPadding(dp(2), dp(7), dp(2), 0)
        }
        content.addView(customHint)
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        actions.addView(button("取消").apply { setOnClickListener { dismiss() } }, weightedParams(true))
        actions.addView(button("应用", emphasized = true).apply {
            setOnClickListener {
                dismissThen {
                    onApply(selectedTheme, selectedBackground, selectedScale)
                }
            }
        }, weightedParams(false))
        content.addView(actions)
    }

    private fun <T> optionRow(
        options: List<Pair<T, String>>,
        target: MutableMap<T, GuardianActionButton>,
        onSelect: (T) -> Unit
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        options.forEachIndexed { index, (value, title) ->
            val option = optionButton(title).apply { setOnClickListener { onSelect(value) } }
            option.tag = title
            target[value] = option
            addView(option, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = dp(4)
                if (index < options.lastIndex) marginEnd = dp(4)
            })
        }
    }

    private fun refreshSelection() {
        val themeEnabled = selectedBackground != BackgroundPreference.SHAO_OU
        themeButtons.forEach { (value, button) ->
            styleButton(button, value == selectedTheme)
            button.isEnabled = themeEnabled
            if (!themeEnabled) {
                button.contentDescription =
                    if (value == ThemePreference.DARK) "深色，已选择，少偶背景固定主题"
                    else "${button.tag}，少偶背景下不可用"
            }
        }
        backgroundButtons.forEach { (value, button) -> styleButton(button, value == selectedBackground) }
        val scaleEnabled = selectedBackground == BackgroundPreference.CUSTOM_IMAGE
        scaleButtons.forEach { (value, button) ->
            button.isEnabled = scaleEnabled
            styleButton(
                button,
                selectedBackground != BackgroundPreference.SOLID && value == selectedScale
            )
        }
        if (::customHint.isInitialized) {
            customHint.visibility =
                if (selectedBackground == BackgroundPreference.CUSTOM_IMAGE) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun styleButton(button: GuardianActionButton, selected: Boolean) {
        val baseLabel = button.tag?.toString() ?: button.text.toString().removePrefix("✓ ")
        button.isSelected = selected
        button.contentDescription =
            "$baseLabel，${if (selected) "已选择" else "未选择"}"
        button.text = if (selected) "✓ $baseLabel" else baseLabel
        button.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        button.setTextColor(
            if (selected) {
                if (dark || palette.imageContrast) Color.WHITE else 0xFF006E78.toInt()
            } else foreground
        )
        (button.background as? LiquidGlassDrawable)?.setSelected(selected)
        button.isSelected = selected
    }

    private fun sectionCaption(text: String) =
        label(text, if (tabletDialog) 15.5f else 13f, false).apply {
        setPadding(dp(2), dp(16), dp(2), dp(7))
    }

    private fun label(text: String, size: Float, bold: Boolean) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(
            if (size >= 18f) this@VisualSettingsDialog.foreground
            else this@VisualSettingsDialog.secondary
        )
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        if (palette.imageContrast) {
            setShadowLayer(
                resources.displayMetrics.density * 1.05f,
                0f,
                resources.displayMetrics.density * 0.45f,
                0xA8000000.toInt()
            )
        }
    }

    private fun optionButton(text: String) = GuardianActionButton(context).apply {
        this.text = text
        isAllCaps = false
        textSize = if (tabletDialog) 16.5f else 13f
        minHeight = dp(if (tabletDialog) 56 else 46)
        setPadding(dp(8), 0, dp(8), 0)
        clipToOutline = false
        background = LiquidGlassDrawable(
            context,
            dp(15).toFloat(),
            palette.imageContrast
        )
        visualBackground?.let(::bindGlassBackground)
        styleButton(this, false)
    }

    private fun weightedParams(first: Boolean) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (first) marginEnd = dp(4) else marginStart = dp(4)
        }
}
