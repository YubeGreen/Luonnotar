package com.yubegreen.luonnotar.ui.visual

import android.content.Context
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider

enum class LiquidGlassBackdropMode {
    NONE,
    TRANSLUCENT,
    FROSTED,
    OPTICAL
}

/** A thin host for the same [LiquidGlassDrawable] used by standalone buttons. */
class LiquidGlassBackdropView(
    context: Context,
    private val radius: Float,
    imageContrast: Boolean
) : View(context) {
    private val material = LiquidGlassDrawable(context, radius, imageContrast)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        background = material
    }

    fun bind(background: VisualBackgroundView, target: View): LiquidGlassBackdropMode {
        visibility = VISIBLE
        return background.bindSurface(material, target)
    }

    fun setGood(good: Boolean) {
        material.setGood(good)
    }

    fun setTouchInteraction(x: Float, y: Float, progress: Float) {
        material.setTouchHighlight(x, y, progress)
    }

    fun release() {
        material.releaseScene()
    }

    override fun onDetachedFromWindow() {
        material.releaseScene()
        super.onDetachedFromWindow()
    }
}
