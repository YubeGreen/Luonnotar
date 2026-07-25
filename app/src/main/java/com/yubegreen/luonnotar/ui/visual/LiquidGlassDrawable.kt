package com.yubegreen.luonnotar.ui.visual

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import kotlin.math.hypot

class LiquidGlassDrawable(
    context: Context,
    private val radius: Float,
    private val imageContrast: Boolean = false
) : Drawable() {
    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    private val boundsRect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var backdropAvailable = false
    private var good = false
    private var selected = false
    private var touchX = 0f
    private var touchY = 0f
    private var touchProgress = 0f

    fun setBackdropAvailable(available: Boolean) {
        if (backdropAvailable == available) return
        backdropAvailable = available
        rebuild()
        invalidateSelf()
    }

    fun setGood(value: Boolean) {
        if (good == value) return
        good = value
        rebuild()
        invalidateSelf()
    }

    fun setSelected(value: Boolean) {
        if (selected == value) return
        selected = value
        rebuild()
        invalidateSelf()
    }

    fun setTouchHighlight(x: Float, y: Float, progress: Float) {
        touchX = x
        touchY = y
        touchProgress = progress.coerceIn(0f, 1f)
        rebuildTouch()
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        boundsRect.set(bounds)
        rebuild()
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(boundsRect, radius, radius, fillPaint)
        if (selectionPaint.color != Color.TRANSPARENT) {
            canvas.drawRoundRect(boundsRect, radius, radius, selectionPaint)
        }
        if (touchPaint.shader != null) {
            canvas.drawRoundRect(boundsRect, radius, radius, touchPaint)
        }
        val inset = borderPaint.strokeWidth / 2f
        boundsRect.inset(inset, inset)
        canvas.drawRoundRect(boundsRect, radius - inset, radius - inset, borderPaint)
        boundsRect.inset(-inset, -inset)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun rebuild() {
        if (boundsRect.isEmpty) return
        val fill = when {
            imageContrast -> 0xA612161C.toInt()
            dark -> if (backdropAvailable) 0x3604070C else 0xE6111114.toInt()
            else -> if (backdropAvailable) 0x24FFFFFF else 0xF2F5F5F7.toInt()
        }
        fillPaint.color = fill
        selectionPaint.color = when {
            !selected -> Color.TRANSPARENT
            dark || imageContrast -> 0x28539ABB
            else -> 0x1A0A84FF
        }
        borderPaint.strokeWidth = resourcesDensity
        val high = when {
            dark || imageContrast -> 0x30FFFFFF
            else -> 0x38000000
        }
        val low = when {
            dark || imageContrast -> 0x0AFFFFFF
            else -> 0x0D000000
        }
        val directionX = 1f
        val directionY = 1f
        val length = hypot(directionX, directionY)
        val extent = hypot(boundsRect.width(), boundsRect.height()) * 0.55f
        borderPaint.shader = LinearGradient(
            boundsRect.centerX() - directionX / length * extent,
            boundsRect.centerY() - directionY / length * extent,
            boundsRect.centerX() + directionX / length * extent,
            boundsRect.centerY() + directionY / length * extent,
            intArrayOf(
                high,
                when {
                    good && (dark || imageContrast) -> 0x4530D158
                    good -> 0x45177754
                    dark || imageContrast -> 0x2A64D2FF
                    else -> 0x2A087D89
                },
                low,
                if (dark || imageContrast) 0x1C7378C7 else 0x1C8993D1
            ),
            floatArrayOf(0f, 0.24f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        rebuildTouch()
    }

    private fun rebuildTouch() {
        if (boundsRect.isEmpty || touchProgress <= 0f) {
            touchPaint.shader = null
            return
        }
        val base = if (dark || imageContrast) Color.WHITE else Color.BLACK
        val alpha = ((if (dark || imageContrast) 34 else 36) * touchProgress).toInt()
        touchPaint.shader = RadialGradient(
            touchX,
            touchY,
            boundsRect.width().coerceAtLeast(boundsRect.height()) * 0.7f,
            intArrayOf(
                ColorUtils.setAlphaComponent(base, alpha),
                ColorUtils.setAlphaComponent(base, (alpha * 0.78f).toInt()),
                ColorUtils.setAlphaComponent(base, (alpha * 0.43f).toInt()),
                ColorUtils.setAlphaComponent(base, (alpha * 0.14f).toInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.2f, 0.48f, 0.76f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private val resourcesDensity = context.resources.displayMetrics.density
}
