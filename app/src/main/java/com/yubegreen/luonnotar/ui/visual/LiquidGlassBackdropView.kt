package com.yubegreen.luonnotar.ui.visual

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider

class LiquidGlassBackdropView(context: Context) : View(context) {
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var backgroundView: VisualBackgroundView? = null
    private var targetView: View? = null
    private var bitmap: Bitmap? = null
    private var baseColor = 0
    private var overlayColor = 0
    private var radius = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    fun bind(
        background: VisualBackgroundView,
        target: View,
        sharedBitmap: Bitmap,
        baseColor: Int,
        overlayColor: Int,
        radius: Float,
        blurRadius: Float
    ) {
        backgroundView = background
        targetView = target
        bitmap = sharedBitmap
        this.baseColor = baseColor
        this.overlayColor = overlayColor
        this.radius = radius
        invalidateOutline()
        visibility = VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(
                RenderEffect.createBlurEffect(
                    blurRadius,
                    blurRadius,
                    Shader.TileMode.DECAL
                )
            )
        }
        invalidate()
    }

    fun release() {
        backgroundView = null
        targetView = null
        bitmap = null
        baseColor = 0
        overlayColor = 0
        visibility = GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRenderEffect(null)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val source = bitmap?.takeUnless { it.isRecycled } ?: return
        val background = backgroundView ?: return
        val target = targetView ?: return
        canvas.drawColor(baseColor)
        if (background.populateBackdropMatrix(target, matrix)) {
            val checkpoint = canvas.save()
            canvas.concat(matrix)
            canvas.drawBitmap(source, 0f, 0f, paint)
            canvas.restoreToCount(checkpoint)
        }
        canvas.drawColor(overlayColor)
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }
}
