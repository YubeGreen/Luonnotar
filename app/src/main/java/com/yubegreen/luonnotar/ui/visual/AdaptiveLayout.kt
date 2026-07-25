package com.yubegreen.luonnotar.ui.visual

import android.content.Context
import android.os.Build
import android.view.WindowManager
import android.view.WindowInsets
import kotlin.math.min

object AdaptiveLayout {
    const val TABLET_MIN_WIDTH_DP = 600
    const val WIDE_TABLET_MIN_WIDTH_DP = 840
    const val CONTENT_MAX_WIDTH_DP = 760
    const val WIDE_CONTENT_MAX_WIDTH_DP = 960
    const val SHEET_MAX_WIDTH_DP = 640

    fun isTablet(context: Context): Boolean =
        context.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP

    fun isWideTablet(context: Context): Boolean =
        isTablet(context) &&
            context.resources.configuration.screenWidthDp >= WIDE_TABLET_MIN_WIDTH_DP

    fun contentMaximumWidthPx(context: Context): Int =
        dp(
            context,
            if (isWideTablet(context)) {
                WIDE_CONTENT_MAX_WIDTH_DP
            } else {
                CONTENT_MAX_WIDTH_DP
            }
        )

    fun dialogWindowWidth(context: Context): Int {
        val windowWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val metrics = context.getSystemService(WindowManager::class.java)
                    ?.currentWindowMetrics
                val safe = metrics?.windowInsets?.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                metrics?.bounds?.width()?.minus((safe?.left ?: 0) + (safe?.right ?: 0))
            }.getOrNull()?.takeIf { it > 0 }
        } else {
            null
        } ?: context.resources.displayMetrics.widthPixels
        return cappedWidth(
            availableWidth = windowWidth,
            maximumWidth = dp(context, SHEET_MAX_WIDTH_DP),
            totalHorizontalMargin = dp(context, 24)
        )
    }

    internal fun cappedWidth(
        availableWidth: Int,
        maximumWidth: Int,
        totalHorizontalMargin: Int
    ): Int = min(
        (availableWidth - totalHorizontalMargin).coerceAtLeast(1),
        maximumWidth
    )

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
