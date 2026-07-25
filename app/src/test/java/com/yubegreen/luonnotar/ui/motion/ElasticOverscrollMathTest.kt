package com.yubegreen.luonnotar.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ElasticOverscrollMathTest {
    @Test
    fun `crossing top preserves unconsumed pull`() {
        assertEquals(70f, ElasticOverscrollMath.unconsumedFingerDelta(100f, 30, 0), 0.01f)
    }

    @Test
    fun `crossing bottom preserves unconsumed pull`() {
        assertEquals(-50f, ElasticOverscrollMath.unconsumedFingerDelta(-150f, 300, 400), 0.01f)
    }

    @Test
    fun `fling travel grows with speed and keeps direction`() {
        val slowTop = ElasticOverscrollMath.flingOffset(-1_200, 3f, 264f)
        val fastTop = ElasticOverscrollMath.flingOffset(-9_000, 3f, 264f)
        val fastBottom = ElasticOverscrollMath.flingOffset(9_000, 3f, 264f)
        assertTrue(slowTop > 0f)
        assertTrue(fastTop > slowTop)
        assertEquals(-fastTop, fastBottom, 0.01f)
    }

    @Test
    fun `fling travel honors maximum`() {
        assertEquals(264f, ElasticOverscrollMath.flingOffset(-100_000, 3f, 264f), 0.01f)
    }

    @Test
    fun `remaining velocity decays while approaching edge`() {
        val initial = ElasticOverscrollMath.remainingFlingVelocity(9_000, 0L)
        val delayed = ElasticOverscrollMath.remainingFlingVelocity(9_000, 600L)
        assertEquals(9_000, initial)
        assertTrue(delayed in 1 until initial)
        assertTrue(
            abs(ElasticOverscrollMath.flingOffset(delayed, 3f, 264f)) <
                abs(ElasticOverscrollMath.flingOffset(initial, 3f, 264f))
        )
    }
}
