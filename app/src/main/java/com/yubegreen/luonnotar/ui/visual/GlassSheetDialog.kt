package com.yubegreen.luonnotar.ui.visual

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.yubegreen.luonnotar.ui.motion.GuardianActionButton
import kotlin.math.abs

open class GlassSheetDialog(
    context: Context,
    protected val preferences: VisualPreferences,
    protected val visualBackground: VisualBackgroundView? = null,
    private val anchorView: View? = null
) : Dialog(context) {
    private val tablet = AdaptiveLayout.isTablet(context)
    protected val palette = GlassSheetPalette.resolve(context, preferences)
    protected val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(if (tablet) 26 else 18),
            dp(if (tablet) 14 else 10),
            dp(if (tablet) 26 else 18),
            dp(if (tablet) 26 else 20)
        )
        clipChildren = false
        clipToPadding = false
    }
    protected val sheet = LiquidGlassPanel(
        context,
        dp(28).toFloat(),
        imageContrast = palette.imageContrast,
        enableBackdropBlur = true
    ).apply {
        setTouchFeedbackEnabled(false)
    }

    private var dismissing = false
    private var afterDismissAction: (() -> Unit)? = null
    private var dragStartRawY = 0f
    private var dragStarted = false
    private var velocityTracker: VelocityTracker? = null
    private val dragSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var backdropProgress = 1f
    private var backdropAnimator: ValueAnimator? = null
    private var appliedDimAmount = -1f
    private var backdropUpdatePosted = false
    private var returnTranslationX = 0f
    private var returnTranslationY = 0f
    private var returnScale = 0.96f

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dragIndicator = View(context).apply {
            alpha = 0.82f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(palette.handle)
                cornerRadius = dp(2).toFloat()
            }
        }
        val dragHandle = FrameLayout(context).apply {
            isFocusable = true
            isClickable = true
            contentDescription = "下拉关闭弹窗"
            setOnTouchListener(::handleDragTouch)
            addView(
                dragIndicator,
                FrameLayout.LayoutParams(dp(38), dp(4), Gravity.CENTER)
            )
        }
        content.addView(
            dragHandle,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28))
        )
        sheet.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        visualBackground?.let(sheet::bindBackground)
        setContentView(sheet)
        setCanceledOnTouchOutside(true)
    }

    protected fun title(text: CharSequence) = TextView(context).apply {
        this.text = text
        textSize = if (tablet) 25f else 20f
        setTextColor(palette.foreground)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        applyImageShadowIfNeeded()
    }

    protected fun body(text: CharSequence, monospace: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = if (tablet) 17f else 14f
        setTextColor(palette.secondary)
        setLineSpacing(0f, if (tablet) 1.24f else 1.18f)
        if (monospace) typeface = android.graphics.Typeface.MONOSPACE
        setTextIsSelectable(monospace)
        applyImageShadowIfNeeded()
    }

    protected fun caption(text: CharSequence) = TextView(context).apply {
        this.text = text
        textSize = if (tablet) 15.5f else 13f
        setTextColor(palette.secondary)
        applyImageShadowIfNeeded()
    }

    protected fun button(
        label: CharSequence,
        emphasized: Boolean = false,
        danger: Boolean = false
    ) = GuardianActionButton(context).apply {
        text = label
        isAllCaps = false
        textSize = if (tablet) 16.5f else 13f
        minHeight = dp(if (tablet) 56 else 48)
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(
            when {
                danger -> palette.danger
                emphasized -> palette.accentText
                else -> palette.foreground
            }
        )
        applyImageShadowIfNeeded()
        background = LiquidGlassDrawable(
            context,
            dp(15).toFloat(),
            palette.imageContrast
        ).also { drawable ->
            drawable.setGood(emphasized)
        }
        this@GlassSheetDialog.visualBackground?.let(::bindGlassBackground)
    }

    private fun TextView.applyImageShadowIfNeeded() {
        if (!palette.imageContrast) return
        setShadowLayer(
            context.resources.displayMetrics.density * 1.05f,
            0f,
            context.resources.displayMetrics.density * 0.45f,
            0xA8000000.toInt()
        )
    }

    protected fun dismissThen(action: () -> Unit) {
        afterDismissAction = action
        dismiss()
    }

    override fun show() {
        val anchorCenter = anchorView
            ?.takeIf { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
            ?.let {
                val location = IntArray(2)
                it.getLocationOnScreen(location)
                floatArrayOf(location[0] + it.width / 2f, location[1] + it.height / 2f)
            }
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                width = dialogWidth()
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                dimAmount = 0f
            }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            decorView.setPadding(dp(12), 0, dp(12), dp(12))
        }
        setBackdropProgress(1f, immediate = true)
        super.show()
        window?.setLayout(dialogWidth(), ViewGroup.LayoutParams.WRAP_CONTENT)
        sheet.alpha = 0f
        sheet.post {
            if (!isShowing || dismissing) return@post
            visualBackground?.let(sheet::bindBackground)
            sheet.refreshBackdrop()
            val location = IntArray(2)
            sheet.getLocationOnScreen(location)
            val centerX = location[0] + sheet.width / 2f
            val centerY = location[1] + sheet.height / 2f
            returnTranslationX = anchorCenter?.let { it[0] - centerX } ?: 0f
            returnTranslationY = anchorCenter?.let { it[1] - centerY } ?: dp(34).toFloat()
            returnScale = anchorView?.width
                ?.takeIf { it > 0 && sheet.width > 0 }
                ?.let { (it.toFloat() / sheet.width).coerceIn(0.74f, 0.88f) }
                ?: 0.96f
            sheet.pivotX = sheet.width / 2f
            sheet.pivotY = sheet.height / 2f
            if (!ValueAnimator.areAnimatorsEnabled()) {
                sheet.alpha = 1f
                sheet.translationX = 0f
                sheet.translationY = 0f
                sheet.scaleX = 1f
                sheet.scaleY = 1f
                setBackdropProgress(0f, immediate = true)
                visualBackground?.invalidateSurfacePositions()
                return@post
            }
            sheet.translationX = returnTranslationX
            sheet.translationY = returnTranslationY
            sheet.scaleX = returnScale
            sheet.scaleY = returnScale
            animateBackdropTo(0f, 280L, ENTER_CURVE)
            sheet.animate()
                .alpha(1f)
                .translationX(0f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(340L)
                .setInterpolator(OPEN_OVERSHOOT)
                .setUpdateListener { visualBackground?.invalidateSurfacePositions() }
                .start()
        }
    }

    override fun dismiss() {
        if (!isShowing || dismissing) {
            if (!isShowing) super.dismiss()
            return
        }
        dismissing = true
        if (!ValueAnimator.areAnimatorsEnabled()) {
            completeDismiss()
            return
        }
        animateBackdropTo(1f, 205L, EXIT_CURVE)
        sheet.animate()
            .alpha(0f)
            .translationX(returnTranslationX)
            .translationY(returnTranslationY)
            .scaleX(returnScale)
            .scaleY(returnScale)
            .setDuration(210L)
            .setInterpolator(EXIT_CURVE)
            .setUpdateListener { visualBackground?.invalidateSurfacePositions() }
            .withEndAction(::completeDismiss)
            .start()
        sheet.postDelayed(::completeDismiss, 235L)
    }

    override fun onStop() {
        backdropAnimator?.cancel()
        sheet.removeCallbacks(backdropUpdateRunnable)
        backdropUpdatePosted = false
        super.onStop()
    }

    private fun handleDragTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sheet.animate().cancel()
                backdropAnimator?.cancel()
                dragStartRawY = event.rawY
                dragStarted = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                dragIndicator(view)?.animate()
                    ?.scaleX(1.18f)
                    ?.alpha(1f)
                    ?.setDuration(90L)
                    ?.start()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val distance = (event.rawY - dragStartRawY).coerceAtLeast(0f)
                if (distance > dragSlop) dragStarted = true
                if (dragStarted) {
                    sheet.translationY = distance
                    val progress = (distance / sheet.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    sheet.alpha = 1f - progress * 0.34f
                    val scale = 1f - progress * 0.012f
                    sheet.scaleX = scale
                    sheet.scaleY = scale
                    setBackdropProgress(progress)
                    visualBackground?.invalidateSurfacePositions()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val velocityY = velocityTracker?.yVelocity ?: 0f
                releaseVelocityTracker()
                restoreDragIndicator(view)
                if (
                    sheet.translationY >= sheet.height * DISMISS_FRACTION ||
                    velocityY >= DISMISS_VELOCITY
                ) {
                    dismissFromDrag()
                } else {
                    reboundAfterDrag()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseVelocityTracker()
                restoreDragIndicator(view)
                reboundAfterDrag()
                return true
            }
        }
        return view.onTouchEvent(event)
    }

    private fun reboundAfterDrag() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            sheet.translationY = 0f
            sheet.alpha = 1f
            sheet.scaleX = 1f
            sheet.scaleY = 1f
            setBackdropProgress(0f, immediate = true)
            visualBackground?.invalidateSurfacePositions()
            return
        }
        animateBackdropTo(0f, 260L, ENTER_CURVE)
        sheet.animate()
            .translationY(0f)
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(260L)
            .setInterpolator(ENTER_CURVE)
            .setUpdateListener { visualBackground?.invalidateSurfacePositions() }
            .start()
    }

    private fun dismissFromDrag() {
        if (dismissing) return
        dismissing = true
        animateBackdropTo(1f, 185L, EXIT_CURVE)
        sheet.animate()
            .translationY(sheet.height.toFloat() + dp(24))
            .alpha(0f)
            .scaleX(0.985f)
            .scaleY(0.985f)
            .setDuration(190L)
            .setInterpolator(EXIT_CURVE)
            .setUpdateListener { visualBackground?.invalidateSurfacePositions() }
            .withEndAction(::completeDismiss)
            .start()
        sheet.postDelayed(::completeDismiss, 220L)
    }

    private fun completeDismiss() {
        if (!isShowing) return
        sheet.animate().setListener(null).cancel()
        backdropAnimator?.cancel()
        setBackdropProgress(1f, immediate = true)
        super.dismiss()
        afterDismissAction?.also { action ->
            afterDismissAction = null
            action()
        }
    }

    private fun releaseVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun dragIndicator(handle: View): View? =
        (handle as? ViewGroup)?.getChildAt(0)

    private fun restoreDragIndicator(handle: View) {
        dragIndicator(handle)?.animate()
            ?.scaleX(1f)
            ?.alpha(0.82f)
            ?.setDuration(180L)
            ?.start()
    }

    private fun animateBackdropTo(
        target: Float,
        duration: Long,
        interpolator: TimeInterpolator
    ) {
        backdropAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            setBackdropProgress(target, immediate = true)
            return
        }
        backdropAnimator = ValueAnimator.ofFloat(backdropProgress, target).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { setBackdropProgress(it.animatedValue as Float) }
            start()
        }
    }

    private fun setBackdropProgress(progress: Float, immediate: Boolean = false) {
        backdropProgress = progress.coerceIn(0f, 1f)
        if (immediate) {
            sheet.removeCallbacks(backdropUpdateRunnable)
            backdropUpdatePosted = false
            applyBackdropProgress()
        } else if (!backdropUpdatePosted) {
            backdropUpdatePosted = true
            sheet.postOnAnimation(backdropUpdateRunnable)
        }
    }

    private val backdropUpdateRunnable = Runnable {
        backdropUpdatePosted = false
        applyBackdropProgress()
    }

    private fun applyBackdropProgress() {
        val dialogWindow = window ?: return
        val visibleProgress = 1f - backdropProgress
        val dimAmount = FULL_DIM_AMOUNT * visibleProgress
        if (abs(dimAmount - appliedDimAmount) < 0.002f) return
        appliedDimAmount = dimAmount
        dialogWindow.attributes = dialogWindow.attributes.apply {
            this.dimAmount = dimAmount
        }
    }

    private fun dialogWidth(): Int {
        return AdaptiveLayout.dialogWindowWidth(context)
    }

    protected fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val DISMISS_FRACTION = 0.22f
        const val DISMISS_VELOCITY = 1_050f
        const val FULL_DIM_AMOUNT = 0.28f
        val ENTER_CURVE = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        val EXIT_CURVE = PathInterpolator(0.4f, 0f, 1f, 1f)
        val OPEN_OVERSHOOT = OvershootInterpolator(0.58f)
    }
}

data class GlassSheetPalette(
    val foreground: Int,
    val secondary: Int,
    val accentText: Int,
    val danger: Int,
    val handle: Int,
    val imageContrast: Boolean
) {
    companion object {
        fun resolve(context: Context, preferences: VisualPreferences): GlassSheetPalette {
            val dark = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val imageContrast = preferences.background != BackgroundPreference.SOLID
            return GlassSheetPalette(
                foreground = if (dark || imageContrast) 0xFFF7F7F9.toInt() else 0xFF10212C.toInt(),
                secondary = if (dark || imageContrast) 0xFFE2E8EE.toInt() else 0xFF425968.toInt(),
                accentText = if (dark || imageContrast) 0xFF7CF2A4.toInt() else 0xFF0E6B48.toInt(),
                danger = if (dark) 0xFFFF6961.toInt() else 0xFFB42318.toInt(),
                handle = if (dark || imageContrast) 0x99FFFFFF.toInt() else 0x66000000,
                imageContrast = imageContrast
            )
        }
    }
}
