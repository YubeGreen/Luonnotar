package com.yubegreen.luonnotar.ui.motion

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

internal object ElasticOverscrollMath {
    fun unconsumedFingerDelta(fingerDelta: Float, scrollBefore: Int, scrollAfter: Int): Float {
        val consumedFingerDelta = (scrollBefore - scrollAfter).toFloat()
        val unconsumed = fingerDelta - consumedFingerDelta
        return if (abs(unconsumed) < 0.5f) 0f else unconsumed
    }

    fun flingOffset(velocityY: Int, density: Float, maxOffset: Float): Float {
        if (velocityY == 0) return 0f
        val speedDpPerSecond = abs(velocityY) / density.coerceAtLeast(1f)
        val distanceDp = ((speedDpPerSecond - 220f) / 115f).coerceAtLeast(0f)
        val direction = if (velocityY > 0) -1f else 1f
        return direction * (distanceDp * density).coerceAtMost(maxOffset)
    }

    fun remainingFlingVelocity(initialVelocityY: Int, elapsedMs: Long): Int {
        if (initialVelocityY == 0) return 0
        val decay = exp(-elapsedMs.coerceAtLeast(0L) / 430.0)
        return (initialVelocityY * decay).roundToInt()
    }

    fun outwardDurationMs(velocityY: Int, density: Float): Long {
        val speedDpPerSecond = abs(velocityY) / density.coerceAtLeast(1f)
        val intensity = (speedDpPerSecond / 5_000f).coerceIn(0f, 1f)
        return (72f + 66f * intensity).roundToInt().toLong()
    }

    fun settleDurationMs(velocityY: Int, density: Float): Long {
        val speedDpPerSecond = abs(velocityY) / density.coerceAtLeast(1f)
        val intensity = (speedDpPerSecond / 5_000f).coerceIn(0f, 1f)
        return (185f + 105f * intensity).roundToInt().toLong()
    }

    fun settleTension(velocityY: Int, density: Float): Float {
        val speedDpPerSecond = abs(velocityY) / density.coerceAtLeast(1f)
        val intensity = (speedDpPerSecond / 5_000f).coerceIn(0f, 1f)
        return 0.22f + 0.24f * intensity
    }
}
