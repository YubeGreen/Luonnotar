package com.yubegreen.luonnotar.ui.motion

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

internal object ElasticOverscrollMath {
    fun unconsumedFingerDelta(fingerDelta: Float, scrollBefore: Int, scrollAfter: Int): Float {
        val consumedFingerDelta = (scrollBefore - scrollAfter).toFloat()
        val unconsumed = fingerDelta - consumedFingerDelta
        return if (abs(unconsumed) < 0.5f) 0f else unconsumed
    }

    fun pullResistance(currentOffset: Float, maxOffset: Float): Float {
        if (maxOffset <= 0f) return 0f
        val progress = (abs(currentOffset) / maxOffset).coerceIn(0f, 1f)
        return 0.28f / (1f + 3.2f * progress * progress)
    }

    fun flingOffset(velocityY: Int, density: Float, maxOffset: Float): Float {
        if (velocityY == 0 || maxOffset <= 0f) return 0f
        val safeDensity = density.coerceAtLeast(1f)
        val speedDpPerSecond = abs(velocityY) / safeDensity
        if (speedDpPerSecond <= 180f) return 0f

        // The edge yields briefly, then gets out of the way instead of advertising the effect.
        val distanceDp = 4f + 23f * ln(1f + (speedDpPerSecond - 180f) / 800f)
        val distancePx = (distanceDp * safeDensity).coerceAtMost(maxOffset)
        val direction = if (velocityY > 0) -1f else 1f
        return direction * distancePx
    }

    fun remainingFlingVelocity(initialVelocityY: Int, elapsedMs: Long): Int {
        if (initialVelocityY == 0) return 0
        val decay = exp(-elapsedMs.coerceAtLeast(0L) / 360.0)
        return (initialVelocityY * decay).roundToInt()
    }

    fun outwardDurationMs(velocityY: Int, density: Float): Long {
        val speedDpPerSecond = abs(velocityY) / density.coerceAtLeast(1f)
        val intensity = (speedDpPerSecond / 5_500f).coerceIn(0f, 1f)
        return (64f + 38f * intensity).roundToInt().toLong()
    }

    fun settleDurationMs(velocityY: Int, density: Float): Long {
        val speedDpPerSecond = abs(velocityY) / density.coerceAtLeast(1f)
        val intensity = (speedDpPerSecond / 5_500f).coerceIn(0f, 1f)
        return (178f + 58f * intensity).roundToInt().toLong()
    }
}
