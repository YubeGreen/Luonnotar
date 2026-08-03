package com.yubegreen.luonnotar.ui.motion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.widget.NestedScrollView
import com.yubegreen.luonnotar.ui.visual.LiquidGlassPanel
import kotlin.math.abs

class ElasticNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.scrollViewStyle
) : NestedScrollView(context, attrs, defStyleAttr) {
    private var lastTouchY = 0f
    private var pendingFlingVelocityY = 0
    private var flingStartedAtMs = 0L
    private var elasticFrameListener: (() -> Unit)? = null
    private var activeGlassPanel: LiquidGlassPanel? = null
    private val hitRect = Rect()
    private val maxElasticOffset by lazy { resources.displayMetrics.density * 28f }
    private val edgeFeatherLength = (resources.displayMetrics.density * 18f).toInt().coerceAtLeast(1)

    init {
        isVerticalFadingEdgeEnabled = true
        isHorizontalFadingEdgeEnabled = false
        setFadingEdgeLength(edgeFeatherLength)
        overScrollMode = View.OVER_SCROLL_NEVER
        isSmoothScrollingEnabled = true
    }

    override fun getTopFadingEdgeStrength(): Float =
        (scrollY / edgeFeatherLength.toFloat()).coerceIn(0f, 1f)

    override fun getBottomFadingEdgeStrength(): Float =
        ((maxScrollRange() - scrollY) / edgeFeatherLength.toFloat()).coerceIn(0f, 1f)

    override fun getLeftFadingEdgeStrength(): Float = 0f
    override fun getRightFadingEdgeStrength(): Float = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            return dispatchTouchWithoutElasticMotion(event)
        }
        val fingerDelta = event.y - lastTouchY
        val scrollBefore = scrollY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pendingFlingVelocityY = 0
                flingStartedAtMs = 0L
                getChildAt(0)?.animate()?.setUpdateListener(null)?.cancel()
                lastTouchY = event.y
                clearActiveGlassPanel()
                activeGlassPanel = findGlassPanelAt(
                    getChildAt(0),
                    event.rawX.toInt(),
                    event.rawY.toInt()
                )
            }
            MotionEvent.ACTION_MOVE -> lastTouchY = event.y
        }
        val handled = super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                activeGlassPanel?.setTouchHighlightFromScreen(event.rawX, event.rawY)
            MotionEvent.ACTION_MOVE -> {
                applyElasticPull(
                    ElasticOverscrollMath.unconsumedFingerDelta(
                        fingerDelta,
                        scrollBefore,
                        scrollY
                    )
                )
                activeGlassPanel?.setTouchHighlightFromScreen(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_UP -> {
                releaseActiveGlassPanel()
                releaseElasticPull()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                releaseActiveGlassPanel()
                releaseElasticPull()
            }
        }
        return handled
    }

    override fun fling(velocityY: Int) {
        if (ValueAnimator.areAnimatorsEnabled()) {
            pendingFlingVelocityY = velocityY
            flingStartedAtMs = SystemClock.uptimeMillis()
        }
        super.fling(velocityY)
    }

    fun setElasticFrameListener(listener: (() -> Unit)?) {
        elasticFrameListener = listener
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        if (ValueAnimator.areAnimatorsEnabled()) maybeStartFlingBounce(top)
    }

    private fun applyElasticPull(deltaY: Float) {
        val content = getChildAt(0) ?: return
        val current = content.translationY
        val atTop = scrollY <= 0
        val atBottom = scrollY >= maxScrollRange()
        val pullTop = atTop && (deltaY > 0f || current > 0f)
        val pullBottom = atBottom && (deltaY < 0f || current < 0f)
        if (!pullTop && !pullBottom) return
        val resistance = ElasticOverscrollMath.pullResistance(current, maxElasticOffset)
        var next = (current + deltaY * resistance).coerceIn(-maxElasticOffset, maxElasticOffset)
        if (pullTop) next = next.coerceAtLeast(0f)
        if (pullBottom) next = next.coerceAtMost(0f)
        content.translationY = next
        elasticFrameListener?.invoke()
    }

    private fun releaseElasticPull(flingVelocityY: Int = 0) {
        val content = getChildAt(0) ?: return
        if (content.translationY == 0f) return
        val density = resources.displayMetrics.density
        val duration = if (flingVelocityY == 0) 210L
        else ElasticOverscrollMath.settleDurationMs(flingVelocityY, density)
        content.animate()
            .translationY(0f)
            .setDuration(duration)
            .setInterpolator(SETTLE_CURVE)
            .setUpdateListener { elasticFrameListener?.invoke() }
            .withEndAction {
                content.animate().setUpdateListener(null)
                elasticFrameListener?.invoke()
            }
            .start()
    }

    private fun maybeStartFlingBounce(scrollTop: Int) {
        val initialVelocityY = pendingFlingVelocityY
        if (initialVelocityY == 0) return
        val reachedTop = initialVelocityY < 0 && scrollTop <= 0
        val reachedBottom = initialVelocityY > 0 && scrollTop >= maxScrollRange()
        if (!reachedTop && !reachedBottom) return
        pendingFlingVelocityY = 0
        val velocityY = ElasticOverscrollMath.remainingFlingVelocity(
            initialVelocityY,
            SystemClock.uptimeMillis() - flingStartedAtMs
        )
        flingStartedAtMs = 0L

        val content = getChildAt(0) ?: return
        if (abs(content.translationY) > 0.5f) return
        val target = ElasticOverscrollMath.flingOffset(
            velocityY,
            resources.displayMetrics.density,
            maxElasticOffset
        )
        if (abs(target) < 0.5f) return
        content.animate()
            .translationY(target)
            .setDuration(ElasticOverscrollMath.outwardDurationMs(velocityY, resources.displayMetrics.density))
            .setInterpolator(OUTWARD_CURVE)
            .setUpdateListener { elasticFrameListener?.invoke() }
            .withEndAction { releaseElasticPull(velocityY) }
            .start()
    }

    private fun maxScrollRange(): Int {
        val content = getChildAt(0) ?: return 0
        return (content.height - height + paddingTop + paddingBottom).coerceAtLeast(0)
    }

    override fun onDetachedFromWindow() {
        clearActiveGlassPanel()
        pendingFlingVelocityY = 0
        flingStartedAtMs = 0L
        getChildAt(0)?.animate()?.setUpdateListener(null)?.cancel()
        elasticFrameListener = null
        super.onDetachedFromWindow()
    }

    private fun dispatchTouchWithoutElasticMotion(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            clearActiveGlassPanel()
            activeGlassPanel = findGlassPanelAt(
                getChildAt(0),
                event.rawX.toInt(),
                event.rawY.toInt()
            )
        }
        val handled = super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                activeGlassPanel?.setTouchHighlightFromScreen(event.rawX, event.rawY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE ->
                releaseActiveGlassPanel()
        }
        return handled
    }

    private fun findGlassPanelAt(view: View?, rawX: Int, rawY: Int): LiquidGlassPanel? {
        if (view == null || view.visibility != View.VISIBLE) return null
        if (
            view is LiquidGlassPanel &&
            view.getGlobalVisibleRect(hitRect) &&
            hitRect.contains(rawX, rawY)
        ) {
            return view
        }
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                findGlassPanelAt(view.getChildAt(index), rawX, rawY)?.let { return it }
            }
        }
        return null
    }

    private fun releaseActiveGlassPanel() {
        activeGlassPanel?.releaseTouchFeedbackFromParent()
        activeGlassPanel = null
    }

    private fun clearActiveGlassPanel() {
        activeGlassPanel?.clearTouchFeedbackFromParent()
        activeGlassPanel = null
    }
    private companion object {
        val OUTWARD_CURVE = PathInterpolator(0.12f, 0.72f, 0.2f, 1f)
        val SETTLE_CURVE = PathInterpolator(0.2f, 0.82f, 0.24f, 1f)
    }

}
