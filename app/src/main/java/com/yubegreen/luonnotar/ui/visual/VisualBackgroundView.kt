package com.yubegreen.luonnotar.ui.visual

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import com.yubegreen.luonnotar.R
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min

/** Receives a coarse local-background luminance without changing the view's text colors. */
interface GlassSceneContrastTarget {
    fun onGlassSceneLuminanceChanged(luminance: Float)
}

/**
 * The single scene source used by every glass surface in this window.
 *
 * The source image is decoded once. A single screen-sized software bitmap is rendered at most once
 * per choreographer frame and all cards sample it in window coordinates. Scrolling only schedules a
 * coalesced coordinate refresh; it never decodes, captures, blurs, or allocates a bitmap per card.
 */
class VisualBackgroundView(context: Context) : View(context) {
    private val sourcePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scenePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sourceMatrix = Matrix()
    private val sceneCanvas = Canvas()
    private val opticalStageCanvas = Canvas()
    private val opticalQuarterCanvas = Canvas()
    private val opticalSceneCanvas = Canvas()
    private val opticalDestination = RectF()
    private val backgroundLocation = IntArray(2)
    private val targetLocation = IntArray(2)
    private val surfaceBindings = WeakHashMap<LiquidGlassDrawable, SurfaceBinding>()
    private var sourceBitmap: Bitmap? = null
    private var sceneBitmap: Bitmap? = null
    private var opticalStageBitmap: Bitmap? = null
    private var opticalQuarterBitmap: Bitmap? = null
    private var opticalSceneBitmap: Bitmap? = null
    private var preferences = VisualPreferences()
    private var sceneDirty = true
    private var frameScheduled = false
    private var sceneGeneration = 0L
    private var sharedBlurEffect: RenderEffect? = null
    private var opticsRenderer: LiquidGlassOpticsRenderer? = null
    private var opticsDisabled = false
    private var opticsFailureLogged = false

    private val sceneFrameRunnable = Runnable {
        frameScheduled = false
        if (!isAttachedToWindow || width <= 0 || height <= 0) return@Runnable
        if (sceneDirty) renderSharedScene()
        invalidate()
        notifyBoundSurfaces()
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        setWillNotDraw(false)
    }

    fun apply(preferences: VisualPreferences) {
        if (this.preferences == preferences && sourceBitmap != null) {
            requestRealtimeSceneFrame(contentChanged = true)
            return
        }
        this.preferences = preferences
        releaseSourceBitmap()
        if (width > 0 && height > 0) loadSourceBitmap()
        requestRealtimeSceneFrame(contentChanged = true)
    }

    /** Coalesces scroll, elastic overscroll, and property-animation alignment to one frame. */
    fun invalidateSurfacePositions() {
        requestRealtimeSceneFrame(contentChanged = false)
    }

    /** Allows a future animated background to mark its shared scene dirty without per-card work. */
    fun requestRealtimeSceneFrame(contentChanged: Boolean = true) {
        if (contentChanged) sceneDirty = true
        if (frameScheduled) return
        frameScheduled = true
        postOnAnimation(sceneFrameRunnable)
    }

    internal fun bindSurface(drawable: LiquidGlassDrawable, target: View): LiquidGlassBackdropMode {
        val mode = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> LiquidGlassBackdropMode.OPTICAL
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> LiquidGlassBackdropMode.FROSTED
            else -> LiquidGlassBackdropMode.TRANSLUCENT
        }
        surfaceBindings[drawable] = SurfaceBinding(WeakReference(target))
        drawable.attachScene(this, target, mode)
        requestRealtimeSceneFrame(contentChanged = sceneBitmap == null)
        return mode
    }

    internal fun unbindSurface(drawable: LiquidGlassDrawable) {
        surfaceBindings.remove(drawable)
    }

    internal fun populateSceneMatrix(target: View, out: Matrix): Boolean {
        val scene = sceneBitmap?.takeUnless { it.isRecycled } ?: return false
        if (scene.width != width || scene.height != height || width <= 0 || height <= 0) {
            return false
        }
        getLocationOnScreen(backgroundLocation)
        target.getLocationOnScreen(targetLocation)
        out.reset()
        out.setTranslate(
            (backgroundLocation[0] - targetLocation[0]).toFloat(),
            (backgroundLocation[1] - targetLocation[1]).toFloat()
        )
        return true
    }

    internal fun sharedSceneBitmap(): Bitmap? =
        sceneBitmap?.takeUnless { it.isRecycled }

    internal fun sharedOpticalSceneBitmap(): Bitmap? =
        opticalSceneBitmap?.takeUnless { it.isRecycled }

    /** Maps the shared prefiltered optical scene into a surface in any window. */
    internal fun populateOpticalSceneMatrix(target: View, out: Matrix): Boolean {
        val scene = sharedOpticalSceneBitmap() ?: return false
        if (width <= 0 || height <= 0 || scene.width <= 0 || scene.height <= 0) return false
        getLocationOnScreen(backgroundLocation)
        target.getLocationOnScreen(targetLocation)
        out.reset()
        out.setScale(
            width.toFloat() / scene.width,
            height.toFloat() / scene.height
        )
        out.postTranslate(
            (backgroundLocation[0] - targetLocation[0]).toFloat(),
            (backgroundLocation[1] - targetLocation[1]).toFloat()
        )
        return true
    }

    @RequiresApi(Build.VERSION_CODES.S)
    internal fun sharedBlurEffect(): RenderEffect {
        return sharedBlurEffect ?: RenderEffect.createBlurEffect(
            24f * resources.displayMetrics.density,
            24f * resources.displayMetrics.density,
            Shader.TileMode.MIRROR
        ).also { sharedBlurEffect = it }
    }

    internal fun drawOpticalSurface(
        canvas: Canvas,
        bounds: RectF,
        radius: Float,
        target: View,
        dark: Boolean,
        localLuminance: Float,
        touchX: Float,
        touchY: Float,
        touchProgress: Float
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || opticsDisabled) return false
        return drawOpticalSurfaceApi33(
            canvas,
            bounds,
            radius,
            target,
            dark,
            localLuminance,
            touchX,
            touchY,
            touchProgress
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawOpticalSurfaceApi33(
        canvas: Canvas,
        bounds: RectF,
        radius: Float,
        target: View,
        dark: Boolean,
        localLuminance: Float,
        touchX: Float,
        touchY: Float,
        touchProgress: Float
    ): Boolean {
        val scene = sharedOpticalSceneBitmap() ?: return false
        getLocationOnScreen(backgroundLocation)
        target.getLocationOnScreen(targetLocation)
        val sceneOffsetX = (targetLocation[0] - backgroundLocation[0]).toFloat()
        val sceneOffsetY = (targetLocation[1] - backgroundLocation[1]).toFloat()
        return try {
            val renderer = opticsRenderer ?: LiquidGlassOpticsRenderer().also {
                opticsRenderer = it
            }
            renderer.draw(
                canvas = canvas,
                bounds = bounds,
                radius = radius,
                sceneSource = scene,
                sceneOffsetX = sceneOffsetX,
                sceneOffsetY = sceneOffsetY,
                sceneScaleX = scene.width.toFloat() / width.coerceAtLeast(1),
                sceneScaleY = scene.height.toFloat() / height.coerceAtLeast(1),
                dark = dark,
                localLuminance = localLuminance,
                touchX = touchX,
                touchY = touchY,
                touchProgress = touchProgress,
                density = resources.displayMetrics.density
            )
            true
        } catch (error: Throwable) {
            opticsRenderer?.clear()
            opticsRenderer = null
            opticsDisabled = true
            if (!opticsFailureLogged) {
                opticsFailureLogged = true
                Log.e("Luonnotar", "liquid_glass_runtime_shader_failed; using safe fallback", error)
            }
            false
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return
        replaceSceneBitmap(width, height)
        releaseSourceBitmap()
        loadSourceBitmap()
        requestRealtimeSceneFrame(contentChanged = true)
    }

    override fun onDraw(canvas: Canvas) {
        val scene = sharedSceneBitmap()
        if (scene != null && !sceneDirty) {
            canvas.drawBitmap(scene, 0f, 0f, scenePaint)
        } else {
            canvas.drawColor(baseColor())
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (width > 0 && height > 0 && sceneBitmap == null) {
            replaceSceneBitmap(width, height)
            if (sourceBitmap == null) loadSourceBitmap()
        }
        requestRealtimeSceneFrame(contentChanged = true)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(sceneFrameRunnable)
        frameScheduled = false
        val iterator = surfaceBindings.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.key.onSceneSourceReleased(this)
            iterator.remove()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clearOpticsRendererApi33()
        }
        sharedBlurEffect = null
        releaseSourceBitmap()
        releaseSceneBitmap()
        super.onDetachedFromWindow()
    }

    private fun renderSharedScene() {
        val scene = sharedSceneBitmap() ?: return
        sceneCanvas.setBitmap(scene)
        sceneCanvas.drawColor(baseColor(), android.graphics.PorterDuff.Mode.SRC)
        val image = sourceBitmap?.takeUnless { it.isRecycled }
        if (image != null) {
            val scale = displayScale(image)
            sourceMatrix.reset()
            sourceMatrix.setScale(scale, scale)
            sourceMatrix.postTranslate(
                (width - image.width * scale) / 2f,
                (height - image.height * scale) / 2f
            )
            sceneCanvas.drawBitmap(image, sourceMatrix, sourcePaint)
            sceneCanvas.drawColor(if (isDarkTheme()) 0x18000000 else 0x08FFFFFF)
        }
        sceneCanvas.setBitmap(null)
        renderOpticalScene(scene)
        sceneDirty = false
        sceneGeneration += 1L
    }

    private fun renderOpticalScene(scene: Bitmap) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val stage = opticalStageBitmap?.takeUnless { it.isRecycled } ?: return
        val quarter = opticalQuarterBitmap?.takeUnless { it.isRecycled } ?: return
        val optical = opticalSceneBitmap?.takeUnless { it.isRecycled } ?: return
        opticalStageCanvas.setBitmap(stage)
        opticalStageCanvas.drawColor(baseColor(), android.graphics.PorterDuff.Mode.SRC)
        opticalDestination.set(0f, 0f, stage.width.toFloat(), stage.height.toFloat())
        opticalStageCanvas.drawBitmap(scene, null, opticalDestination, scenePaint)
        opticalStageCanvas.setBitmap(null)
        opticalQuarterCanvas.setBitmap(quarter)
        opticalQuarterCanvas.drawColor(baseColor(), android.graphics.PorterDuff.Mode.SRC)
        opticalDestination.set(0f, 0f, quarter.width.toFloat(), quarter.height.toFloat())
        opticalQuarterCanvas.drawBitmap(stage, null, opticalDestination, scenePaint)
        opticalQuarterCanvas.setBitmap(null)
        opticalSceneCanvas.setBitmap(optical)
        opticalSceneCanvas.drawColor(baseColor(), android.graphics.PorterDuff.Mode.SRC)
        opticalDestination.set(0f, 0f, optical.width.toFloat(), optical.height.toFloat())
        opticalSceneCanvas.drawBitmap(quarter, null, opticalDestination, scenePaint)
        opticalSceneCanvas.setBitmap(null)
    }

    private fun notifyBoundSurfaces() {
        val iterator = surfaceBindings.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val target = entry.value.target.get()
            if (target == null) {
                entry.key.onSceneSourceReleased(this)
                iterator.remove()
                continue
            }
            if (!target.isAttachedToWindow || !target.isShown) continue
            val luminance = sampleLocalLuminance(target)
            entry.key.onSharedSceneFrame(sceneGeneration, luminance)
            val bucket = luminanceBucket(luminance)
            if (bucket != entry.value.luminanceBucket) {
                entry.value.luminanceBucket = bucket
                (target as? GlassSceneContrastTarget)
                    ?.onGlassSceneLuminanceChanged(luminance)
            }
        }
    }

    private fun sampleLocalLuminance(target: View): Float {
        val scene = sharedSceneBitmap() ?: return if (isDarkTheme()) 0.12f else 0.88f
        getLocationOnScreen(backgroundLocation)
        target.getLocationOnScreen(targetLocation)
        val x = (targetLocation[0] - backgroundLocation[0] + target.width / 2)
            .coerceIn(0, scene.width - 1)
        val y = (targetLocation[1] - backgroundLocation[1] + target.height / 2)
            .coerceIn(0, scene.height - 1)
        val color = scene.getPixel(x, y)
        val red = Color.red(color) / 255f
        val green = Color.green(color) / 255f
        val blue = Color.blue(color) / 255f
        return 0.2126f * red + 0.7152f * green + 0.0722f * blue
    }

    private fun luminanceBucket(luminance: Float): Int = when {
        luminance < 0.34f -> 0
        luminance > 0.72f -> 2
        else -> 1
    }

    private fun replaceSceneBitmap(width: Int, height: Int) {
        val current = sceneBitmap
        if (current != null && !current.isRecycled && current.width == width && current.height == height) {
            replaceOpticalSceneBitmaps(width, height)
            sceneDirty = true
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clearOpticsRendererApi33()
        }
        current?.takeUnless { it.isRecycled }?.recycle()
        sceneBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        replaceOpticalSceneBitmaps(width, height)
        sceneDirty = true
    }

    private fun replaceOpticalSceneBitmaps(width: Int, height: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val stageWidth = max(1, (width + 1) / 2)
        val stageHeight = max(1, (height + 1) / 2)
        val quarterWidth = max(1, (stageWidth + 1) / 2)
        val quarterHeight = max(1, (stageHeight + 1) / 2)
        val opticalWidth = max(1, (quarterWidth + 1) / 2)
        val opticalHeight = max(1, (quarterHeight + 1) / 2)
        val stageCurrent = opticalStageBitmap
        val quarterCurrent = opticalQuarterBitmap
        val opticalCurrent = opticalSceneBitmap
        if (
            stageCurrent != null && !stageCurrent.isRecycled &&
            stageCurrent.width == stageWidth && stageCurrent.height == stageHeight &&
            quarterCurrent != null && !quarterCurrent.isRecycled &&
            quarterCurrent.width == quarterWidth && quarterCurrent.height == quarterHeight &&
            opticalCurrent != null && !opticalCurrent.isRecycled &&
            opticalCurrent.width == opticalWidth && opticalCurrent.height == opticalHeight
        ) {
            return
        }
        stageCurrent?.takeUnless { it.isRecycled }?.recycle()
        quarterCurrent?.takeUnless { it.isRecycled }?.recycle()
        opticalCurrent?.takeUnless { it.isRecycled }?.recycle()
        opticalStageBitmap = Bitmap.createBitmap(stageWidth, stageHeight, Bitmap.Config.ARGB_8888)
        opticalQuarterBitmap = Bitmap.createBitmap(quarterWidth, quarterHeight, Bitmap.Config.ARGB_8888)
        opticalSceneBitmap = Bitmap.createBitmap(opticalWidth, opticalHeight, Bitmap.Config.ARGB_8888)
    }

    private fun loadSourceBitmap() {
        sourceBitmap = when (preferences.background) {
            BackgroundPreference.SOLID -> null
            BackgroundPreference.SHAO_OU -> BackgroundImageStore.decodeResourceForDisplay(
                context,
                R.drawable.background_shaoou,
                width,
                height
            )
            BackgroundPreference.CUSTOM_IMAGE -> BackgroundImageStore.decodeCustomForDisplay(
                context,
                width,
                height
            )
        }
    }

    private fun releaseSourceBitmap() {
        sourceBitmap?.takeUnless { it.isRecycled }?.recycle()
        sourceBitmap = null
    }

    private fun releaseSceneBitmap() {
        sceneCanvas.setBitmap(null)
        opticalStageCanvas.setBitmap(null)
        opticalQuarterCanvas.setBitmap(null)
        opticalSceneCanvas.setBitmap(null)
        sceneBitmap?.takeUnless { it.isRecycled }?.recycle()
        opticalStageBitmap?.takeUnless { it.isRecycled }?.recycle()
        opticalQuarterBitmap?.takeUnless { it.isRecycled }?.recycle()
        opticalSceneBitmap?.takeUnless { it.isRecycled }?.recycle()
        sceneBitmap = null
        opticalStageBitmap = null
        opticalQuarterBitmap = null
        opticalSceneBitmap = null
        sceneDirty = true
    }

    private fun displayScale(image: Bitmap): Float = when (preferences.backgroundScale) {
        BackgroundScale.FILL_CROP -> max(width.toFloat() / image.width, height.toFloat() / image.height)
        BackgroundScale.FIT_CENTER -> min(width.toFloat() / image.width, height.toFloat() / image.height)
    }

    private fun baseColor(): Int = if (isDarkTheme()) 0xFF07090D.toInt() else 0xFFF6F8FC.toInt()

    internal fun isDarkTheme(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun clearOpticsRendererApi33() {
        opticsRenderer?.clear()
        opticsRenderer = null
    }

    private data class SurfaceBinding(
        val target: WeakReference<View>,
        var luminanceBucket: Int = -1
    )
}
