package com.yubegreen.luonnotar.notification

import android.content.Context
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsImportanceFencePolicyTest {
    @Test
    fun `binding flags always request auto create important and above client`() {
        val flags = GmsImportanceFencePolicy.bindingFlags(Build.VERSION_CODES.O)
        assertTrue(flags and Context.BIND_AUTO_CREATE != 0)
        assertTrue(flags and Context.BIND_IMPORTANT != 0)
        assertTrue(flags and Context.BIND_ABOVE_CLIENT != 0)
        assertFalse(flags and Context.BIND_INCLUDE_CAPABILITIES != 0)
    }

    @Test
    fun `android ten binding includes client capabilities`() {
        val flags = GmsImportanceFencePolicy.bindingFlags(Build.VERSION_CODES.Q)
        assertTrue(flags and Context.BIND_INCLUDE_CAPABILITIES != 0)
    }

    @Test
    fun `lease extension never exceeds four minute hard deadline`() {
        assertEquals(
            250_000L,
            GmsImportanceFencePolicy.extendedDeadline(
                startedElapsed = 10_000L,
                currentDeadlineElapsed = 130_000L,
                nowElapsed = 130_000L,
                requestedDurationMs = 120_000L
            )
        )
        assertEquals(
            250_000L,
            GmsImportanceFencePolicy.extendedDeadline(
                startedElapsed = 10_000L,
                currentDeadlineElapsed = 250_000L,
                nowElapsed = 240_000L,
                requestedDurationMs = 120_000L
            )
        )
    }

    @Test
    fun `persistent slot advances only while a fallback remains`() {
        assertTrue(GmsImportanceFencePolicy.shouldTryNextCandidate(0, 2))
        assertFalse(GmsImportanceFencePolicy.shouldTryNextCandidate(1, 2))
        assertFalse(GmsImportanceFencePolicy.shouldTryNextCandidate(-1, 2))
    }
}
