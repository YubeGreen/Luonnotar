package com.yubegreen.luonnotar.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class GmsBinderAnchorBackoffPolicyTest {
    @Test
    fun `backoff is fast first then capped indefinitely`() {
        assertEquals(30_000L, GmsBinderAnchorBackoffPolicy.delayMs(1))
        assertEquals(60_000L, GmsBinderAnchorBackoffPolicy.delayMs(2))
        assertEquals(300_000L, GmsBinderAnchorBackoffPolicy.delayMs(3))
        assertEquals(900_000L, GmsBinderAnchorBackoffPolicy.delayMs(4))
        assertEquals(900_000L, GmsBinderAnchorBackoffPolicy.delayMs(6))
        assertEquals(900_000L, GmsBinderAnchorBackoffPolicy.delayMs(100))
    }
}
