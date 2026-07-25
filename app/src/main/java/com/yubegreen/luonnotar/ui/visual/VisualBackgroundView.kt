package com.yubegreen.luonnotar.ui.visual

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.view.View
import com.yubegreen.luonnotar.R
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min

class VisualBackgroundView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    private var preferences = VisualPreferences()
    private val backgroundLocation = IntArray(2)
    private val targetLocation = IntArray(2)
    private val backdropBindings =
        WeakHashMap<LiquidGlassBackdropView, BackdropBinding>()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
    }

    fun apply(preferences: VisualPreferences) {
        this.preferences = preferences
        releaseBitmap()
        if (width > 0 && height > 0) {
            loadBitmap()
            refreshBoundBackdrops()
        }
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && height > 0 && preferences.background != BackgroundPreference.SOLID) {
            releaseBitmap()
            loadBitmap()
            refreshBoundBackdrops()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        canvas.drawColor(if (dark) 0xFF0B0B0D.toInt() else 0xFFF5F5F7.toInt())
        val image = bitmap?.takeUnless { it.isRecycled } ?: return
        val scale = when (preferences.backgroundScale) {
            BackgroundScale.FILL_CROP -> max(width.toFloat() / image.width, height.toFloat() / image.height)
            BackgroundScale.FIT_CENTER -> min(width.toFloat() / image.width, height.toFloat() / image.height)
        }
        val left = (width - image.width * scale) / 2f
        val top = (height - image.height * scale) / 2f
        val checkpoint = canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        canvas.drawBitmap(image, 0f, 0f, paint)
        canvas.restoreToCount(checkpoint)
        canvas.drawColor(
            when {
                dark -> 0x59000000
                preferences.background != BackgroundPreference.SOLID -> 0x4D000000
                else -> Color.TRANSPARENT
            }
        )
    }

    internal fun bindBackdrop(
        backdrop: LiquidGlassBackdropView,
        target: View,
        radius: Float
    ): Boolean {
        backdropBindings[backdrop] = BackdropBinding(WeakReference(target), radius)
        val image = bitmap?.takeUnless { it.isRecycled }
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            preferences.background == BackgroundPreference.SOLID ||
            image == null
        ) {
            backdrop.release()
            return false
        }
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        backdrop.bind(
            background = this,
            target = target,
            sharedBitmap = image,
            baseColor = if (dark || preferences.background != BackgroundPreference.SOLID) {
                0xFF0B0B0D.toInt()
            } else {
                0xFFF5F5F7.toInt()
            },
            overlayColor = if (dark || preferences.background != BackgroundPreference.SOLID) {
                0x52000000
            } else {
                Color.TRANSPARENT
            },
            radius = radius,
            blurRadius = 36f * resources.displayMetrics.density
        )
        return true
    }

    internal fun populateBackdropMatrix(target: View, out: Matrix): Boolean {
        val image = bitmap?.takeUnless { it.isRecycled } ?: return false
        if (width <= 0 || height <= 0) return false
        val scale = when (preferences.backgroundScale) {
            BackgroundScale.FILL_CROP -> max(width.toFloat() / image.width, height.toFloat() / image.height)
            BackgroundScale.FIT_CENTER -> min(width.toFloat() / image.width, height.toFloat() / image.height)
        }
        val left = (width - image.width * scale) / 2f
        val top = (height - image.height * scale) / 2f
        getLocationOnScreen(backgroundLocation)
        target.getLocationOnScreen(targetLocation)
        out.reset()
        out.setScale(scale, scale)
        out.postTranslate(
            backgroundLocation[0] + left - targetLocation[0],
            backgroundLocation[1] + top - targetLocation[1]
        )
        return true
    }

    override fun onDetachedFromWindow() {
        releaseBitmap()
        backdropBindings.clear()
        super.onDetachedFromWindow()
    }

    private fun loadBitmap() {
        bitmap = when (preferences.background) {
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

    private fun releaseBitmap() {
        backdropBindings.keys.toList().forEach(LiquidGlassBackdropView::release)
        bitmap?.takeUnless { it.isRecycled }?.recycle()
        bitmap = null
    }

    private fun refreshBoundBackdrops() {
        backdropBindings.entries.toList().forEach { (backdrop, binding) ->
            val target = binding.target.get()
            if (target == null || !target.isAttachedToWindow) {
                backdropBindings.remove(backdrop)
                backdrop.release()
            } else {
                bindBackdrop(backdrop, target, binding.radius)
            }
        }
    }

    private data class BackdropBinding(
        val target: WeakReference<View>,
        val radius: Float
    )
}
