package com.yubegreen.luonnotar.ui.visual

import android.content.Context
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.LinearLayout
import kotlin.math.min

class GlassMessageDialog(
    context: Context,
    preferences: VisualPreferences,
    visualBackground: VisualBackgroundView?,
    title: CharSequence,
    message: CharSequence,
    closeLabel: CharSequence = "关闭",
    primaryLabel: CharSequence? = null,
    secondaryLabel: CharSequence? = null,
    monospace: Boolean = false,
    anchorView: View? = null,
    private val onPrimary: (() -> Unit)? = null,
    private val onSecondary: (() -> Unit)? = null,
    private val onClose: (() -> Unit)? = null
) : GlassSheetDialog(
    context,
    preferences,
    visualBackground,
    anchorView
) {
    init {
        content.addView(title(title))
        val messageView = body(message, monospace).apply {
            maxHeight = min(
                dp(380),
                (context.resources.displayMetrics.heightPixels * 0.48f).toInt()
            )
            movementMethod = ScrollingMovementMethod.getInstance()
        }
        content.addView(
            messageView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
        )
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        actions.addView(
            button(closeLabel).apply {
                setOnClickListener {
                    if (onClose == null) dismiss() else dismissThen(onClose)
                }
            },
            weighted(first = true)
        )
        if (secondaryLabel != null) {
            actions.addView(
                button(secondaryLabel).apply {
                    setOnClickListener { dismissThen { onSecondary?.invoke() } }
                },
                weighted(first = false)
            )
        }
        if (primaryLabel != null) {
            actions.addView(
                button(primaryLabel, emphasized = true).apply {
                    setOnClickListener { dismissThen { onPrimary?.invoke() } }
                },
                weighted(first = false)
            )
        }
        content.addView(actions)
    }

    private fun weighted(first: Boolean) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (first) marginEnd = dp(4) else marginStart = dp(4)
        }
}
