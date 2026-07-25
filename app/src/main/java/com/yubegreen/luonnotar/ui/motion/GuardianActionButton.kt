package com.yubegreen.luonnotar.ui.motion

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatButton
import com.yubegreen.luonnotar.ui.visual.LiquidGlassDrawable

class GuardianActionButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatButton(context, attrs) {
    init {
        isClickable = true
        isFocusable = true
        stateListAnimator = null
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        (background as? LiquidGlassDrawable)?.setSelected(selected)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.46f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                (background as? LiquidGlassDrawable)
                    ?.setTouchHighlight(event.x, event.y, 1f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE ->
                (background as? LiquidGlassDrawable)
                    ?.setTouchHighlight(event.x, event.y, 0f)
        }
        if (ValueAnimator.areAnimatorsEnabled()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pivotX = event.x
                    pivotY = event.y
                    animate().cancel()
                    animate()
                        .scaleX(0.98f)
                        .scaleY(0.98f)
                        .setDuration(100L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_MOVE -> Unit
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                    animate().cancel()
                    animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(220L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                true
            }
            MotionEvent.ACTION_MOVE -> {
                isPressed = event.x in 0f..width.toFloat() &&
                    event.y in 0f..height.toFloat()
                true
            }
            MotionEvent.ACTION_UP -> {
                val shouldClick = isPressed
                isPressed = false
                if (shouldClick) performClick()
                true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                isPressed = false
                true
            }
            else -> true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) clearPressFeedback()
    }

    override fun onDetachedFromWindow() {
        clearPressFeedback()
        super.onDetachedFromWindow()
    }

    private fun clearPressFeedback() {
        isPressed = false
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        alpha = if (isEnabled) 1f else 0.46f
        (background as? LiquidGlassDrawable)?.setTouchHighlight(0f, 0f, 0f)
    }
}
