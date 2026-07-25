package com.yubegreen.luonnotar.ui.visual

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

class LiquidGlassPanel(
    context: Context,
    private val radius: Float,
    imageContrast: Boolean,
    private val enableBackdropBlur: Boolean = true
) : FrameLayout(context) {
    private val backdrop = LiquidGlassBackdropView(context)
    private val surfaceDrawable = LiquidGlassDrawable(context, radius, imageContrast)
    private val surface = View(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        background = surfaceDrawable
    }
    private val contentLayer = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
    }
    private var internalsReady = false
    private var visualBackground: VisualBackgroundView? = null
    private var touchActive = false
    private var touchFeedbackEnabled = false
    private var touchAnimator: ValueAnimator? = null
    private var touchX = 0f
    private var touchY = 0f
    private var touchProgress = 0f
    private var touchTarget = 0f
    private val visibleRect = Rect()
    private val screenLocation = IntArray(2)

    init {
        clipChildren = false
        clipToPadding = false
        elevation = context.resources.displayMetrics.density * 2f
        super.addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        super.addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        super.addView(contentLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        internalsReady = true
    }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        if (internalsReady && child !== backdrop && child !== surface && child !== contentLayer) {
            contentLayer.addView(child, index.coerceAtMost(contentLayer.childCount), params)
        } else {
            super.addView(child, index, params)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        contentLayer.measure(widthMeasureSpec, heightMeasureSpec)
        val measuredWidth = resolveSize(contentLayer.measuredWidth, widthMeasureSpec)
        val measuredHeight = resolveSize(contentLayer.measuredHeight, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
        val exactWidth = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val exactHeight = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        backdrop.measure(exactWidth, exactHeight)
        surface.measure(exactWidth, exactHeight)
        contentLayer.measure(exactWidth, exactHeight)
    }

    fun bindBackground(background: VisualBackgroundView) {
        visualBackground = background
        post(::refreshBackdrop)
    }

    fun refreshBackdrop() {
        val background = visualBackground ?: return
        if (!enableBackdropBlur) {
            backdrop.release()
            surfaceDrawable.setBackdropAvailable(false)
            return
        }
        if (!getGlobalVisibleRect(visibleRect) || visibleRect.isEmpty) {
            backdrop.release()
            surfaceDrawable.setBackdropAvailable(false)
            return
        }
        val available = background.bindBackdrop(backdrop, this, radius)
        surfaceDrawable.setBackdropAvailable(available)
    }

    fun invalidateBackdropPosition() {
        if (!enableBackdropBlur) return
        if (backdrop.visibility == VISIBLE) backdrop.invalidate()
    }

    fun setGood(good: Boolean) {
        surfaceDrawable.setGood(good)
    }

    fun setTouchFeedbackEnabled(enabled: Boolean) {
        touchFeedbackEnabled = enabled
        if (!enabled) {
            clearTouchFeedback()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (touchFeedbackEnabled) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginTouchFeedback(event.x, event.y)
                }
                MotionEvent.ACTION_MOVE -> if (touchActive) {
                    moveTouchHighlight(event.x, event.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE ->
                    releaseTouchFeedback()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!touchFeedbackEnabled) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) clearTouchFeedback()
    }

    override fun onDetachedFromWindow() {
        clearTouchFeedback()
        backdrop.release()
        super.onDetachedFromWindow()
    }

    internal fun setTouchHighlightFromScreen(rawX: Float, rawY: Float) {
        if (!touchFeedbackEnabled) return
        getLocationOnScreen(screenLocation)
        val localX = rawX - screenLocation[0]
        val localY = rawY - screenLocation[1]
        if (touchActive) {
            moveTouchHighlight(localX, localY)
        } else {
            beginTouchFeedback(localX, localY)
        }
    }

    internal fun releaseTouchFeedbackFromParent() {
        releaseTouchFeedback()
    }

    internal fun clearTouchFeedbackFromParent() {
        clearTouchFeedback()
    }

    private fun releaseTouchFeedback() {
        if (!touchActive) return
        touchActive = false
        hideTouchHighlight()
        animate().cancel()
        if (ValueAnimator.areAnimatorsEnabled()) {
            animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(RELEASE_DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            scaleX = 1f
            scaleY = 1f
        }
    }

    private fun clearTouchFeedback() {
        touchActive = false
        touchAnimator?.cancel()
        touchAnimator = null
        touchProgress = 0f
        touchTarget = 0f
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        surfaceDrawable.setTouchHighlight(0f, 0f, 0f)
    }

    private fun beginTouchFeedback(x: Float, y: Float) {
        val newlyActive = !touchActive
        touchActive = true
        showTouchHighlight(x, y)
        if (newlyActive && ValueAnimator.areAnimatorsEnabled()) {
            pivotX = width / 2f
            pivotY = height / 2f
            animate().cancel()
            animate()
                .scaleX(PRESSED_SCALE)
                .scaleY(PRESSED_SCALE)
                .setDuration(PRESS_DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun showTouchHighlight(x: Float, y: Float) {
        touchX = x
        touchY = y
        if (!ValueAnimator.areAnimatorsEnabled()) {
            touchTarget = TOUCH_TARGET
            setTouchProgress(TOUCH_TARGET)
        } else if (touchTarget != TOUCH_TARGET) {
            animateTouchHighlight(TOUCH_TARGET, TOUCH_ENTER_MS)
        } else {
            surfaceDrawable.setTouchHighlight(touchX, touchY, touchProgress)
        }
    }

    private fun moveTouchHighlight(x: Float, y: Float) {
        touchX = x
        touchY = y
        if (touchTarget != TOUCH_TARGET) {
            showTouchHighlight(x, y)
        } else {
            surfaceDrawable.setTouchHighlight(touchX, touchY, touchProgress)
        }
    }

    private fun hideTouchHighlight() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            touchTarget = 0f
            setTouchProgress(0f)
        } else if (touchTarget != 0f) {
            animateTouchHighlight(0f, TOUCH_EXIT_MS)
        }
    }

    private fun animateTouchHighlight(target: Float, durationMs: Long) {
        touchAnimator?.cancel()
        touchTarget = target
        touchAnimator = ValueAnimator.ofFloat(touchProgress, target).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { setTouchProgress(it.animatedValue as Float) }
            start()
        }
    }

    private fun setTouchProgress(progress: Float) {
        touchProgress = progress.coerceIn(0f, 1f)
        surfaceDrawable.setTouchHighlight(touchX, touchY, touchProgress)
    }

    private companion object {
        const val PRESSED_SCALE = 0.992f
        const val PRESS_DURATION_MS = 100L
        const val RELEASE_DURATION_MS = 220L
        const val TOUCH_TARGET = 0.65f
        const val TOUCH_ENTER_MS = 120L
        const val TOUCH_EXIT_MS = 160L
    }
}
