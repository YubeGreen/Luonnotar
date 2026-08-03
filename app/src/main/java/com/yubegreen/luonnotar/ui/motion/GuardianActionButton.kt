package com.yubegreen.luonnotar.ui.motion

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatButton
import com.yubegreen.luonnotar.ui.visual.GlassSceneContrastTarget
import com.yubegreen.luonnotar.ui.visual.LiquidGlassDrawable
import com.yubegreen.luonnotar.ui.visual.VisualBackgroundView
import java.lang.ref.WeakReference

class GuardianActionButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatButton(context, attrs), GlassSceneContrastTarget {
    private var glassBackgroundReference: WeakReference<VisualBackgroundView>? = null
    private var contrastBucket = -1
    private var touchAnimator: ValueAnimator? = null
    private var touchX = 0f
    private var touchY = 0f
    private var touchProgress = 0f

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

    fun bindGlassBackground(background: VisualBackgroundView) {
        glassBackgroundReference = WeakReference(background)
        (this.background as? LiquidGlassDrawable)?.let { drawable ->
            background.bindSurface(drawable, this)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX = event.x
                touchY = event.y
                animateTouchHighlight(0.72f, 110L)
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
                applyTouchHighlight()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                touchX = event.x
                touchY = event.y
                animateTouchHighlight(0f, 170L)
            }
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
                        .setUpdateListener {
                            glassBackgroundReference?.get()?.invalidateSurfacePositions()
                        }
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
                        .setUpdateListener {
                            glassBackgroundReference?.get()?.invalidateSurfacePositions()
                        }
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glassBackgroundReference?.get()?.let(::bindGlassBackground)
    }

    override fun onDetachedFromWindow() {
        clearPressFeedback()
        (background as? LiquidGlassDrawable)?.releaseScene()
        super.onDetachedFromWindow()
    }

    override fun onGlassSceneLuminanceChanged(luminance: Float) {
        val bucket = when {
            luminance < 0.34f -> 0
            luminance > 0.72f -> 2
            else -> 1
        }
        if (bucket == contrastBucket) return
        contrastBucket = bucket
        val density = resources.displayMetrics.density
        val imageContrast = (background as? LiquidGlassDrawable)?.usesImageContrast() == true
        setShadowLayer(
            density * if (imageContrast) 1.05f else 0.72f,
            0f,
            density * if (imageContrast) 0.45f else 0.35f,
            if (imageContrast) 0xA8000000.toInt()
            else if (luminance > 0.52f) 0x52000000 else 0x42FFFFFF
        )
    }

    private fun clearPressFeedback() {
        isPressed = false
        touchAnimator?.cancel()
        touchAnimator = null
        touchProgress = 0f
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        alpha = if (isEnabled) 1f else 0.46f
        (background as? LiquidGlassDrawable)?.setTouchHighlight(0f, 0f, 0f)
    }

    private fun animateTouchHighlight(target: Float, durationMs: Long) {
        touchAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            touchProgress = target
            applyTouchHighlight()
            return
        }
        touchAnimator = ValueAnimator.ofFloat(touchProgress, target).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                touchProgress = it.animatedValue as Float
                applyTouchHighlight()
            }
            start()
        }
    }

    private fun applyTouchHighlight() {
        (background as? LiquidGlassDrawable)
            ?.setTouchHighlight(touchX, touchY, touchProgress)
    }
}
