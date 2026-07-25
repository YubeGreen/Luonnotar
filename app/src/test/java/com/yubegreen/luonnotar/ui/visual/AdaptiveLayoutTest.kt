package com.yubegreen.luonnotar.ui.visual

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveLayoutTest {
    @Test
    fun `phone sheet keeps edge margin`() {
        assertEquals(
            1056,
            AdaptiveLayout.cappedWidth(
                availableWidth = 1080,
                maximumWidth = 1280,
                totalHorizontalMargin = 24
            )
        )
    }

    @Test
    fun `tablet sheet is capped`() {
        assertEquals(
            1280,
            AdaptiveLayout.cappedWidth(
                availableWidth = 2560,
                maximumWidth = 1280,
                totalHorizontalMargin = 48
            )
        )
    }

    @Test
    fun `tiny split window remains measurable`() {
        assertEquals(
            1,
            AdaptiveLayout.cappedWidth(
                availableWidth = 20,
                maximumWidth = 1280,
                totalHorizontalMargin = 48
            )
        )
    }
}
