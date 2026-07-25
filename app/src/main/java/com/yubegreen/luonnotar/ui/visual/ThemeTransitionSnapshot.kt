package com.yubegreen.luonnotar.ui.visual

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View

object ThemeTransitionSnapshot {
    private var snapshot: Bitmap? = null

    @Synchronized
    fun capture(view: View) {
        if (view.width <= 0 || view.height <= 0) return
        val next = runCatching {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
        }.getOrNull() ?: return
        snapshot?.takeUnless { it.isRecycled }?.recycle()
        snapshot = next
    }

    @Synchronized
    fun take(): Bitmap? = snapshot.also { snapshot = null }
}
