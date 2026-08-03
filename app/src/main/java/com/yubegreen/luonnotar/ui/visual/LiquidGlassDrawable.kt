package com.yubegreen.luonnotar.ui.visual

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference
import kotlin.math.hypot

/**
 * The single material entry point for cards and buttons.
 *
 * When bound, it samples [VisualBackgroundView]'s one shared scene. The optical shader is the only
 * tint owner on API 33+; lower tiers apply one restrained material tint here. Borders, selection,
 * status, and touch feedback never color-wash the whole surface.
 */
class LiquidGlassDrawable(
    context: Context,
    private val radius: Float,
    private val imageContrast: Boolean = false
) : Drawable() {
    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    private val density = context.resources.displayMetrics.density
    private val boundsRect = RectF()
    private val sceneMatrix = Matrix()
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val opticalFallbackTintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val innerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val touchMatrix = Matrix()
    private val clipPath = Path()
    private var backgroundReference: WeakReference<VisualBackgroundView>? = null
    private var targetReference: WeakReference<android.view.View>? = null
    private var backdropMode = LiquidGlassBackdropMode.NONE
    private var good = false
    private var selected = false
    private var touchX = 0f
    private var touchY = 0f
    private var touchProgress = 0f
    private var localLuminance = if (dark) 0.12f else 0.88f
    private var luminanceBucket = if (dark) 0 else 2
    private var touchShaderUsesLight = dark
    private var touchShader: RadialGradient? = null
    private var blurRenderNode: Any? = null

    internal fun attachScene(
        background: VisualBackgroundView,
        target: android.view.View,
        mode: LiquidGlassBackdropMode
    ) {
        backgroundReference = WeakReference(background)
        targetReference = WeakReference(target)
        if (mode == LiquidGlassBackdropMode.FROSTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            prepareBlurRenderNodeApi31(background)
        }
        if (backdropMode != mode) {
            backdropMode = mode
            rebuildMaterial()
        }
        invalidateSelf()
    }

    fun releaseScene() {
        val background = backgroundReference?.get()
        backgroundReference = null
        targetReference = null
        background?.unbindSurface(this)
        backdropMode = LiquidGlassBackdropMode.NONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) releaseBlurRenderNodeApi31()
        rebuildMaterial()
        invalidateSelf()
    }

    internal fun onSceneSourceReleased(background: VisualBackgroundView) {
        if (backgroundReference?.get() !== background) return
        backgroundReference = null
        targetReference = null
        backdropMode = LiquidGlassBackdropMode.NONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) releaseBlurRenderNodeApi31()
        rebuildMaterial()
        invalidateSelf()
    }

    internal fun onSharedSceneFrame(generation: Long, luminance: Float) {
        @Suppress("UNUSED_VARIABLE") val currentGeneration = generation
        localLuminance = luminance.coerceIn(0f, 1f)
        val newBucket = when {
            localLuminance < 0.34f -> 0
            localLuminance > 0.72f -> 2
            else -> 1
        }
        if (newBucket != luminanceBucket) {
            luminanceBucket = newBucket
            rebuildMaterial()
        }
        invalidateSelf()
    }

    fun setBackdropMode(mode: LiquidGlassBackdropMode) {
        if (backdropMode == mode) return
        backdropMode = mode
        rebuildMaterial()
        invalidateSelf()
    }

    fun setGood(value: Boolean) {
        if (good == value) return
        good = value
        rebuildMaterial()
        invalidateSelf()
    }

    fun setSelected(value: Boolean) {
        if (selected == value) return
        selected = value
        rebuildMaterial()
        invalidateSelf()
    }

    fun setTouchHighlight(x: Float, y: Float, progress: Float) {
        touchX = x
        touchY = y
        touchProgress = progress.coerceIn(0f, 1f)
        updateTouchMatrix()
        invalidateSelf()
    }

    internal fun usesImageContrast(): Boolean = imageContrast

    override fun onBoundsChange(bounds: Rect) {
        boundsRect.set(bounds)
        clipPath.reset()
        clipPath.addRoundRect(boundsRect, radius, radius, Path.Direction.CW)
        rebuildMaterial()
    }

    override fun draw(canvas: Canvas) {
        if (boundsRect.isEmpty) return
        val background = backgroundReference?.get()
        val target = targetReference?.get()
        var opticalDrawn = false
        if (
            backdropMode == LiquidGlassBackdropMode.OPTICAL &&
            background != null &&
            target != null
        ) {
            opticalDrawn = background.drawOpticalSurface(
                canvas = canvas,
                bounds = boundsRect,
                radius = radius,
                target = target,
                dark = dark,
                localLuminance = localLuminance,
                touchX = touchX,
                touchY = touchY,
                touchProgress = touchProgress
            )
        }
        if (!opticalDrawn) {
            drawSharedSceneFallback(canvas, background, target)
            canvas.drawRoundRect(
                boundsRect,
                radius,
                radius,
                if (backdropMode == LiquidGlassBackdropMode.OPTICAL) {
                    opticalFallbackTintPaint
                } else {
                    tintPaint
                }
            )
            if (touchProgress > 0f && touchPaint.shader != null) {
                canvas.drawRoundRect(boundsRect, radius, radius, touchPaint)
            }
        }
        if (selectionPaint.color != Color.TRANSPARENT) {
            canvas.drawRoundRect(boundsRect, radius, radius, selectionPaint)
        }
        drawBorders(canvas)
    }

    override fun setAlpha(alpha: Int) {
        val safe = alpha.coerceIn(0, 255)
        backdropPaint.alpha = safe
        tintPaint.alpha = safe
        opticalFallbackTintPaint.alpha = safe
        borderPaint.alpha = safe
        innerBorderPaint.alpha = safe
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        backdropPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun drawSharedSceneFallback(
        canvas: Canvas,
        background: VisualBackgroundView?,
        target: android.view.View?
    ) {
        if (background == null || target == null) return
        val opticalFallback = backdropMode == LiquidGlassBackdropMode.OPTICAL
        val scene = if (opticalFallback) {
            background.sharedOpticalSceneBitmap()
        } else {
            background.sharedSceneBitmap()
        } ?: return
        val positioned = if (opticalFallback) {
            background.populateOpticalSceneMatrix(target, sceneMatrix)
        } else {
            background.populateSceneMatrix(target, sceneMatrix)
        }
        if (!positioned) return
        if (
            backdropMode == LiquidGlassBackdropMode.FROSTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            drawBlurredSceneApi31(canvas, scene, background)
        ) {
            return
        }
        val checkpoint = canvas.save()
        canvas.clipPath(clipPath)
        canvas.concat(sceneMatrix)
        canvas.drawBitmap(scene, 0f, 0f, backdropPaint)
        canvas.restoreToCount(checkpoint)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun prepareBlurRenderNodeApi31(background: VisualBackgroundView) {
        if (blurRenderNode != null) return
        blurRenderNode = RenderNode("LuonnotarGlassSurface").apply {
            setRenderEffect(background.sharedBlurEffect())
            setClipToBounds(true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun drawBlurredSceneApi31(
        canvas: Canvas,
        scene: android.graphics.Bitmap,
        background: VisualBackgroundView
    ): Boolean {
        prepareBlurRenderNodeApi31(background)
        val node = blurRenderNode as? RenderNode ?: return false
        val width = boundsRect.width().toInt().coerceAtLeast(1)
        val height = boundsRect.height().toInt().coerceAtLeast(1)
        node.setPosition(
            boundsRect.left.toInt(),
            boundsRect.top.toInt(),
            boundsRect.left.toInt() + width,
            boundsRect.top.toInt() + height
        )
        val recording = node.beginRecording(width, height)
        recording.translate(-boundsRect.left, -boundsRect.top)
        recording.concat(sceneMatrix)
        recording.drawBitmap(scene, 0f, 0f, backdropPaint)
        node.endRecording()
        val checkpoint = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRenderNode(node)
        canvas.restoreToCount(checkpoint)
        return true
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun releaseBlurRenderNodeApi31() {
        (blurRenderNode as? RenderNode)?.discardDisplayList()
        blurRenderNode = null
    }

    private fun drawBorders(canvas: Canvas) {
        val outerInset = borderPaint.strokeWidth / 2f
        boundsRect.inset(outerInset, outerInset)
        canvas.drawRoundRect(
            boundsRect,
            (radius - outerInset).coerceAtLeast(0f),
            (radius - outerInset).coerceAtLeast(0f),
            borderPaint
        )
        boundsRect.inset(-outerInset, -outerInset)

        if (backdropMode == LiquidGlassBackdropMode.OPTICAL) {
            val innerInset = density * 1.35f
            boundsRect.inset(innerInset, innerInset)
            canvas.drawRoundRect(
                boundsRect,
                (radius - innerInset).coerceAtLeast(0f),
                (radius - innerInset).coerceAtLeast(0f),
                innerBorderPaint
            )
            boundsRect.inset(-innerInset, -innerInset)
        }
    }

    private fun rebuildMaterial() {
        if (boundsRect.isEmpty) return
        val optical = backdropMode == LiquidGlassBackdropMode.OPTICAL
        tintPaint.color = when {
            optical -> Color.TRANSPARENT
            dark && backdropMode == LiquidGlassBackdropMode.FROSTED -> 0x2403070D
            dark -> 0x1F03070D
            backdropMode == LiquidGlassBackdropMode.FROSTED -> 0x0FFFFFFF
            else -> 0x12FFFFFF
        }
        opticalFallbackTintPaint.color = if (dark) 0x1F03070D else 0x0FFFFFFF
        selectionPaint.color = Color.TRANSPARENT

        borderPaint.strokeWidth = when {
            selected -> density * 2.15f
            optical -> density * 1.15f
            else -> density
        }
        innerBorderPaint.strokeWidth = if (selected) density * 0.9f else density * 0.58f
        val darkLocal = localLuminance < 0.48f
        val high = if (darkLocal) 0x66FFFFFF else 0x4A000000
        val low = if (darkLocal) 0x0FFFFFFF else 0x12000000
        val statusAccent = when {
            good && darkLocal -> 0x8A30D158.toInt()
            good -> 0x84177754.toInt()
            selected && darkLocal -> 0xE864D2FF.toInt()
            selected -> 0xE6007180.toInt()
            darkLocal -> 0x3E8ADDF8
            else -> 0x40087D89
        }
        val extent = hypot(boundsRect.width(), boundsRect.height()) * 0.55f
        borderPaint.shader = LinearGradient(
            boundsRect.centerX() - extent * 0.7071f,
            boundsRect.centerY() - extent * 0.7071f,
            boundsRect.centerX() + extent * 0.7071f,
            boundsRect.centerY() + extent * 0.7071f,
            intArrayOf(high, statusAccent, low, if (darkLocal) 0x286E78C7 else 0x288993D1),
            floatArrayOf(0f, 0.25f, 0.73f, 1f),
            Shader.TileMode.CLAMP
        )
        innerBorderPaint.shader = LinearGradient(
            boundsRect.left,
            boundsRect.top,
            boundsRect.right,
            boundsRect.bottom,
            intArrayOf(
                if (darkLocal) 0x2EFFFFFF else 0x2AFFFFFF,
                Color.TRANSPARENT,
                if (darkLocal) 0x16000000 else 0x10000000
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        rebuildTouchShaderIfNeeded()
    }

    private fun rebuildTouchShaderIfNeeded() {
        val useLight = localLuminance < 0.54f
        if (touchShader != null && touchShaderUsesLight == useLight) {
            updateTouchMatrix()
            return
        }
        touchShaderUsesLight = useLight
        val base = if (useLight) Color.WHITE else Color.BLACK
        touchShader = RadialGradient(
            0f,
            0f,
            1f,
            intArrayOf(
                base and 0x00FFFFFF or 0x24000000,
                base and 0x00FFFFFF or 0x16000000,
                base and 0x00FFFFFF or 0x09000000,
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.32f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        ).also { touchPaint.shader = it }
        updateTouchMatrix()
    }

    private fun updateTouchMatrix() {
        val shader = touchShader ?: return
        val touchRadius = maxOf(boundsRect.width(), boundsRect.height()) * 0.68f
        touchMatrix.reset()
        touchMatrix.setScale(touchRadius, touchRadius)
        touchMatrix.postTranslate(touchX, touchY)
        shader.setLocalMatrix(touchMatrix)
        touchPaint.alpha = (255f * touchProgress).toInt().coerceIn(0, 255)
    }
}
